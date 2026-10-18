package kr.co.nexacore.jetkvm

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import kotlin.math.min

/** Sizes the actual SurfaceView to fit: exact weighted LinearLayout sizes can otherwise crop video. */
class VideoViewport(context: Context, private val video: View) : ViewGroup(context) {
    private var videoWidth = 16
    private var videoHeight = 9
    init {
        // Background belongs on the parent, never on the SurfaceView itself.
        setBackgroundColor(Color.BLACK)
        addView(video,LayoutParams(-1,-1))
    }
    fun setVideoSize(width: Int,height: Int) {
        if (width <= 0 || height <= 0) return
        videoWidth = width; videoHeight = height; requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        // Measure the SurfaceView exactly once. A preliminary full-size measurement
        // competes with WebRTC's own resolution-triggered layout requests.
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth,widthMeasureSpec),getDefaultSize(suggestedMinimumHeight,heightMeasureSpec))
        val scale = min(measuredWidth.toDouble()/videoWidth,measuredHeight.toDouble()/videoHeight)
        val fitWidth = (videoWidth*scale).toInt().coerceIn(0,measuredWidth)
        val fitHeight = (videoHeight*scale).toInt().coerceIn(0,measuredHeight)
        video.measure(MeasureSpec.makeMeasureSpec(fitWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(fitHeight,MeasureSpec.EXACTLY))
    }
    override fun onLayout(changed: Boolean,left: Int,top: Int,right: Int,bottom: Int) {
        val x = (width-video.measuredWidth)/2
        val y = (height-video.measuredHeight)/2
        video.layout(x,y,x+video.measuredWidth,y+video.measuredHeight)
        if (changed) android.util.Log.i("JetKvmVideo","FIT source=${videoWidth}x${videoHeight} viewport=${width}x${height} video=${video.width}x${video.height}")
    }
}
