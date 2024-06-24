package me.saket.telephoto.zoomable

import androidx.annotation.FloatRange
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection

/**
 * Create a [ZoomableState] that can be used with [Modifier.zoomable].
 *
 * @param zoomSpec See [ZoomSpec.maxZoomFactor] and [ZoomSpec.preventOverOrUnderZoom].
 *
 * @param autoApplyTransformations Determines whether the resulting scale and translation of pan and zoom
 * gestures should be automatically applied by [Modifier.zoomable] to its content. This can be disabled
 * if your content prefers applying the transformations in a bespoke manner.
 *
 * @param hardwareShortcutsSpec Spec used for handling keyboard and mouse shortcuts, or
 * [HardwareShortcutsSpec.Disabled] for disabling them.
 */
@Composable
fun rememberZoomableState(
  zoomSpec: ZoomSpec = ZoomSpec(),
  autoApplyTransformations: Boolean = true,
  hardwareShortcutsSpec: HardwareShortcutsSpec = HardwareShortcutsSpec(),
): ZoomableState {
  val isLayoutPreview = LocalInspectionMode.current
  val state = rememberSaveable(saver = RealZoomableState.Saver) {
    RealZoomableState(
      autoApplyTransformations = autoApplyTransformations,
      isLayoutPreview = isLayoutPreview,
    )
  }.also {
    it.zoomSpec = zoomSpec
    it.hardwareShortcutsSpec = hardwareShortcutsSpec
    it.layoutDirection = LocalLayoutDirection.current
  }

  if (state.isReadyToInteract) {
    LaunchedEffect(
      state.contentLayoutSize,
      state.contentAlignment,
      state.contentScale,
      state.layoutDirection,
    ) {
      state.refreshContentTransformation()
    }
  }
  return state
}

@Stable
sealed interface ZoomableState {

  /**
   * Transformations that should be applied to [Modifier.zoomable]'s content.
   *
   * See [ZoomableContentTransformation].
   */
  val contentTransformation: ZoomableContentTransformation

  /**
   * Determines whether the resulting scale and translation of pan and zoom gestures
   * should be automatically applied to by [Modifier.zoomable] to its content. This can
   * be disabled if your content prefers applying the transformations in a bespoke manner.
   * */
  var autoApplyTransformations: Boolean

  /**
   * Single source of truth for your content's aspect ratio. If you're using `Modifier.zoomable()`
   * with `Image()` or other composables that also accept [ContentScale], they should not be used
   * to avoid any conflicts.
   *
   * A visual guide of the various scale values can be found
   * [here](https://developer.android.com/jetpack/compose/graphics/images/customize#content-scale).
   */
  var contentScale: ContentScale

  /**
   * Alignment of the content.
   *
   * When the content is zoomed, it is scaled with respect to this alignment until it
   * is large enough to fill all available space. After that, they're scaled uniformly.
   * */
  var contentAlignment: Alignment

  /**
   * The visual bounds of the content, calculated by applying the scale and translation of pan and zoom
   * gestures to the value given to [ZoomableState.setContentLocation]. Useful for drawing decorations
   * around the content or performing hit tests.
   *
   * Because [ZoomableState.setContentLocation] can only be called asynchronously, this may be one frame
   * behind the UI.
   */
  val transformedContentBounds: Rect

  /**
   * The content's current zoom as a fraction of its min and max allowed zoom factors.
   *
   * @return A value between 0 and 1, where 0 indicates that the content is fully zoomed out,
   * 1 indicates that the content is fully zoomed in, and `null` indicates that an initial zoom
   * value hasn't been calculated yet and the content is hidden. A `null` value could be safely
   * treated the same as 0, but [Modifier.zoomable] leaves that decision up to you.
   */
  @get:FloatRange(from = 0.0, to = 1.0)
  val zoomFraction: Float?

  /** See [ZoomableContentLocation]. */
  suspend fun setContentLocation(location: ZoomableContentLocation)

  /** Reset content to its minimum zoom and zero offset. */
  suspend fun resetZoom(withAnimation: Boolean = true)

  /**
   * Zoom around [centroid] by a ratio of [zoomFactor] over the current size and suspend until
   * its finished.
   *
   * @param zoomFactor Ratio over the current size by which to zoom. For example, if [zoomFactor]
   * is `3f`, zoom will be increased 3 fold from the current value.
   *
   * @param centroid the focal point for this zoom within the content's size. Defaults to the center
   * of the content.
   */
  suspend fun zoomBy(
    zoomFactor: Float,
    centroid: Offset = Offset.Unspecified,
    withAnimation: Boolean = true,
  )

  /**
   * Animate pan by [offset] Offset in pixels and suspend until its finished.
   */
  suspend fun panBy(
    offset: Offset,
    withAnimation: Boolean = true,
  )
}
