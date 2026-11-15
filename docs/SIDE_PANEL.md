# Side panel next to CarPlay (experimental)

The side panel shares the main screen with CarPlay without reconnecting. Enable it under **Settings → Advanced → experimental display settings**; the in-session menu then offers **Side panel**. It defaults to off.

## Using it

- Open it by dragging the tab on the passenger-side edge inward. In landscape, CarPlay stays on the driver's side; in portrait, the panel sits below it. The tab indicator fades after ten seconds but remains touchable.
- Drag the edge facing CarPlay to resize. On release, CarPlay takes the nearest of two thirds, one half or one third of the screen; the choice is remembered.
- Drag the edge toward the far side to close, or use the in-session menu. Passing five sixths returns CarPlay to the whole screen.
- The edge tab is inset 20 dp to avoid conflicts with head-unit edge gestures. Panel touches stay in DiPlay; touches in the CarPlay area continue to the iPhone.

On the ECARX port, the panel contains only a clock. It does not provide vehicle readings, climate controls or a replacement launcher.

## Layout and lifetime

The panel uses the [CarPlay view areas](VIEW_AREAS.md). The iPhone renders into the selected area, while an Android view covers the remaining strip. Placement follows physical video coordinates, including letterboxing; switching UI language does not move the panel across CarPlay.

Window changes select the appropriate view area and end the current panel layout. A host adopting a running session can restore the panel. Closing the host or session releases its callbacks and visual effects. Ownership is in [CarPlaySidePanel](../common/src/main/java/com/shihab/diplay/CarPlaySidePanel.kt), with the host supplying session geometry and actions.

The resize blur uses Android 12+ effects and is unavailable on the production Android 9 target. Current vehicle validation scope belongs in the [acceptance matrix](RUST_CORE_REMAINING.zh-CN.md).
