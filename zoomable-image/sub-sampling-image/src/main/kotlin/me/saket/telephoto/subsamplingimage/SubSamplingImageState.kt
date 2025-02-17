@file:Suppress("NAME_SHADOWING")
@file:OptIn(ExperimentalCoroutinesApi::class)

package me.saket.telephoto.subsamplingimage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.fastAny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.plus
import me.saket.telephoto.subsamplingimage.internal.BitmapCache
import me.saket.telephoto.subsamplingimage.internal.BitmapRegionTileGrid
import me.saket.telephoto.subsamplingimage.internal.BitmapSampleSize
import me.saket.telephoto.subsamplingimage.internal.CanvasRegionTile
import me.saket.telephoto.subsamplingimage.internal.ExifMetadata
import me.saket.telephoto.subsamplingimage.internal.ExifMetadata.ImageOrientation
import me.saket.telephoto.subsamplingimage.internal.ImageRegionDecoder
import me.saket.telephoto.subsamplingimage.internal.LocalImageRegionDecoderFactory
import me.saket.telephoto.subsamplingimage.internal.PooledImageRegionDecoder
import me.saket.telephoto.subsamplingimage.internal.calculateFor
import me.saket.telephoto.subsamplingimage.internal.contains
import me.saket.telephoto.subsamplingimage.internal.fastMapNotNull
import me.saket.telephoto.subsamplingimage.internal.generate
import me.saket.telephoto.subsamplingimage.internal.maxScale
import me.saket.telephoto.subsamplingimage.internal.minDimension
import me.saket.telephoto.subsamplingimage.internal.overlaps
import me.saket.telephoto.subsamplingimage.internal.scaledAndOffsetBy
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableContentTransformation
import me.saket.telephoto.zoomable.ZoomableState
import java.io.IOException

/**
 * Create a [SubSamplingImageState] that can be used with [SubSamplingImage] which uses
 * [Modifier.zoomable][me.saket.telephoto.zoomable.zoomable] as its gesture detector.
 *
 * ```kotlin
 * val zoomableState = rememberZoomableState()
 * val imageState = rememberSubSamplingImageState(
 *   zoomableState = zoomableState,
 *   imageSource = SubSamplingImageSource.asset("fox.jpg")
 * )
 *
 * SubSamplingImage(
 *   modifier = Modifier
 *     .fillMaxSize()
 *     .zoomable(zoomableState),
 *   state = imageState,
 *   contentDescription = …,
 * )
 * ```
 */
@Composable
fun rememberSubSamplingImageState(
  imageSource: SubSamplingImageSource,
  zoomableState: ZoomableState,
  imageOptions: ImageBitmapOptions = ImageBitmapOptions.Default,
  errorReporter: SubSamplingImageErrorReporter = SubSamplingImageErrorReporter.NoOpInRelease
): SubSamplingImageState {
  val state = rememberSubSamplingImageState(
    imageSource = imageSource,
    transformation = zoomableState.contentTransformation,
    imageOptions = imageOptions,
    errorReporter = errorReporter,
  )

  // SubSamplingImage will apply the transformations on its own.
  DisposableEffect(state) {
    val previousValue = zoomableState.autoApplyTransformations
    zoomableState.autoApplyTransformations = false
    onDispose {
      zoomableState.autoApplyTransformations = previousValue
    }
  }

  LaunchedEffect(state.imageSize) {
    zoomableState.setContentLocation(
      ZoomableContentLocation.unscaledAndTopLeftAligned(state.imageSize?.toSize())
    )
  }

  return state
}

@Composable
internal fun rememberSubSamplingImageState(
  imageSource: SubSamplingImageSource,
  transformation: ZoomableContentTransformation,
  imageOptions: ImageBitmapOptions = ImageBitmapOptions.Default,
  errorReporter: SubSamplingImageErrorReporter = SubSamplingImageErrorReporter.NoOpInRelease
): SubSamplingImageState {
  val errorReporter by rememberUpdatedState(errorReporter)
  val transformation by rememberUpdatedState(transformation)
  val decoder: ImageRegionDecoder? = createRegionDecoder(imageSource, imageOptions, errorReporter)

  val state = remember(imageSource) {
    RealSubSamplingImageState(imageSource)
  }

  // Reset everything when a new image is set.
  DisposableEffect(state, decoder) {
    state.imageSize = decoder?.imageSize
    onDispose {
      state.tiles = emptyList()
    }
  }
  DisposableEffect(imageSource) {
    onDispose {
      imageSource.close()
    }
  }

  if (decoder != null) {
    val transformations = remember { snapshotFlow { transformation } }

    val scope = rememberCoroutineScope()
    LaunchedEffect(state, transformations, decoder) {
      val bitmapCache = BitmapCache(scope + Dispatchers.IO, decoder)
      val canvasSizeChanges = snapshotFlow { state.canvasSize }
        .filterNotNull()
        .filter { it.minDimension > 0f }

      canvasSizeChanges.flatMapLatest { canvasSize ->
        val tileGrid = BitmapRegionTileGrid.generate(
          canvasSize = canvasSize,
          unscaledImageSize = decoder.imageSize,
        )

        combine(
          transformations,
          bitmapCache.cachedBitmaps()
        ) { transformation, bitmaps ->
          val sampleSize = BitmapSampleSize.calculateFor(transformation.scale.maxScale)
          val foregroundRegions = tileGrid.foreground[sampleSize].orEmpty()

          val foregroundTiles = foregroundRegions.fastMapNotNull { tile ->
            val drawBounds = tile.bounds.scaledAndOffsetBy(transformation.scale, transformation.offset)
            if (drawBounds.overlaps(canvasSize)) {
              CanvasRegionTile(
                bounds = drawBounds,
                bitmap = bitmaps[tile],
                bitmapRegion = tile,
                isBaseTile = false,
                orientation = decoder.imageOrientation,
              )
            } else {
              null
            }
          }

          // Fill any missing gaps in tiles by drawing the low-res base tile underneath as
          // a fallback. The base tile will hide again when all bitmaps have been loaded.
          val canDrawBaseTile = foregroundTiles.isEmpty() || foregroundTiles.fastAny { it.bitmap == null }

          // The base tile needs to be always present even if it isn't going to
          // be drawn. Otherwise BitmapCache will remove its bitmap from cache.
          val baseTile = if (canDrawBaseTile) {
            tileGrid.base.let { tile ->
              val bitmap = bitmaps[tile]
              CanvasRegionTile(
                bounds = tile.bounds.scaledAndOffsetBy(transformation.scale, transformation.offset),
                bitmap = bitmap ?: imageSource.preview,
                orientation = if (bitmap == null) ImageOrientation.None else decoder.imageOrientation,
                bitmapRegion = tile,
                isBaseTile = true,
              )
            }
          } else null

          // Side effect, ew :(.
          bitmapCache.loadOrUnloadForTiles(
            tiles = listOf(tileGrid.base) + foregroundTiles
              .sortedByDescending { it.bounds.contains(transformation.centroid) }
              .map { it.bitmapRegion },
          )

          return@combine (listOfNotNull(baseTile) + foregroundTiles)
        }
      }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)
        .collect { tiles ->
          state.tiles = tiles
        }
    }
  }

  return state
}

@Composable
private fun createRegionDecoder(
  imageSource: SubSamplingImageSource,
  imageOptions: ImageBitmapOptions,
  errorReporter: SubSamplingImageErrorReporter
): ImageRegionDecoder? {
  val context = LocalContext.current
  val errorReporter by rememberUpdatedState(errorReporter)
  var decoder by remember(imageSource) { mutableStateOf<ImageRegionDecoder?>(null) }

  if (!LocalInspectionMode.current) {
    val factory = PooledImageRegionDecoder.Factory(LocalImageRegionDecoderFactory.current)
    LaunchedEffect(imageSource) {
      try {
        val exif = ExifMetadata.read(context, imageSource)
        decoder = factory.create(
          ImageRegionDecoder.FactoryParams(
            context = context,
            imageSource = imageSource,
            imageOptions = imageOptions,
            exif = exif,
          )
        )
      } catch (e: IOException) {
        errorReporter.onImageLoadingFailed(e, imageSource)
      }
    }
    DisposableEffect(imageSource) {
      onDispose {
        decoder?.close()
        decoder = null
      }
    }
  }

  return decoder
}

/** State for [SubSamplingImage]. */
@Stable
sealed interface SubSamplingImageState {
  val imageSize: IntSize?

  /**
   * Whether all the visible tiles have been loaded and the image is displayed (not necessarily in its full quality).
   *
   * Also see [isImageLoadedInFullQuality].
   */
  val isImageLoaded: Boolean

  /** Whether all the visible and *full resolution* tiles have been loaded and the image is displayed. */
  val isImageLoadedInFullQuality: Boolean
}
