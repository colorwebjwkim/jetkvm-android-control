package kr.co.nexacore.jetkvm

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import org.webrtc.JavaI420Buffer
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import kotlin.math.abs

/** Nonuniform corner markers detect cropped content, not just a visible surface. */
object VideoSurfaceSmoke {
    private data class Format(val width: Int,val height: Int,val rotation: Int = 0) {
        val label get() = "${width}x${height}-r$rotation"
        val displayWidth get() = if (rotation % 180 == 0) width else height
        val displayHeight get() = if (rotation % 180 == 0) height else width
    }
    private fun pattern(format: Format): VideoFrame {
        val buffer = JavaI420Buffer.allocate(format.width,format.height)
        val colors = arrayOf(intArrayOf(82,90,240),intArrayOf(145,54,34),intArrayOf(41,240,110),intArrayOf(235,128,128),intArrayOf(126,128,128))
        fun color(x: Int,y: Int): IntArray {
            val left = x < format.width/12; val right = x >= format.width*11/12
            val top = y < format.height/12; val bottom = y >= format.height*11/12
            return colors[when { left && top -> 0; right && top -> 1; left && bottom -> 2; right && bottom -> 3; else -> 4 }]
        }
        for (y in 0 until format.height) for (x in 0 until format.width) buffer.dataY.put(y*buffer.strideY+x,color(x,y)[0].toByte())
        for (y in 0 until format.height/2) for (x in 0 until format.width/2) {
            val color = color(x*2,y*2)
            buffer.dataU.put(y*buffer.strideU+x,color[1].toByte()); buffer.dataV.put(y*buffer.strideV+x,color[2].toByte())
        }
        return VideoFrame(buffer,format.rotation,System.nanoTime())
    }
    private fun matches(pixel: Int,color: Int) = when(color) {
        0 -> Color.red(pixel) > 180 && Color.green(pixel) < 90 && Color.blue(pixel) < 90
        1 -> Color.green(pixel) > 180 && Color.red(pixel) < 90 && Color.blue(pixel) < 90
        2 -> Color.blue(pixel) > 180 && Color.red(pixel) < 90 && Color.green(pixel) < 90
        else -> Color.red(pixel) > 180 && Color.green(pixel) > 180 && Color.blue(pixel) > 180
    }
    fun run(instrumentation: Instrumentation) = with(instrumentation) {
        val report = Bundle()
        var activity: MainActivity? = null
        var session: NativeSessionView? = null
        var failure: Exception? = null
        var passed = 0
        try {
            org.webrtc.PeerConnectionFactory.initialize(org.webrtc.PeerConnectionFactory.InitializationOptions.builder(targetContext).createInitializationOptions())
            activity = startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            lateinit var renderer: SurfaceViewRenderer
            lateinit var viewport: VideoViewport
            val density = activity.resources.displayMetrics.density
            runOnMainSync {
                session = NativeSessionView(activity,"http://127.0.0.1:1")
                val container = FrameLayout(activity).apply {
                    setBackgroundColor(Color.rgb(230,235,241))
                    setOnApplyWindowInsetsListener { view,insets ->
                        view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom); insets
                    }
                }
                container.addView(session,FrameLayout.LayoutParams(-1,-1))
                activity.setContentView(container); container.requestApplyInsets()
                viewport = session!!.getChildAt(2) as VideoViewport
                renderer = viewport.getChildAt(0) as SurfaceViewRenderer
                // No manual setVideoSize: exercise the real renderer resolution callback.
            }
            val sizes = listOf(Triple("tablet",-1,-1),Triple("phone320",320,640),Triple("phone360",360,720),Triple("landscape640",640,320))
            val formats = listOf(Format(320,180),Format(320,240),Format(320,200),Format(420,180),Format(180,320),Format(320,180,90),Format(320,180,180),Format(320,180,270),Format(1280,720))
            sizes.forEach { (name,width,height) ->
                runOnMainSync {
                    session!!.layoutParams = FrameLayout.LayoutParams(if (width < 0) -1 else (width*density).toInt(),if (height < 0) -1 else (height*density).toInt(),Gravity.CENTER)
                }
                formats.forEach { format ->
                    val label = "$name-${format.label}"
                    val frame = pattern(format)
                    try { repeat(12) { renderer.onFrame(frame); Thread.sleep(65) } } finally { frame.release() }
                    waitForIdleSync()
                    val position = IntArray(2)
                    var videoWidth = 0; var videoHeight = 0
                    var borderX = -1; var borderY = -1
                    runOnMainSync {
                        renderer.getLocationOnScreen(position); videoWidth = renderer.width; videoHeight = renderer.height
                        check(videoWidth > 0 && videoHeight > 0) { "Collapsed video: $label" }
                        check(abs(videoWidth.toDouble()/videoHeight - format.displayWidth.toDouble()/format.displayHeight) < 0.02) { "Wrong aspect ratio: $label, ${videoWidth}x${videoHeight}" }
                        check(renderer.left >= 0 && renderer.top >= 0 && renderer.right <= viewport.width && renderer.bottom <= viewport.height) { "Clipped video bounds: $label" }
                        val viewportPosition = IntArray(2); viewport.getLocationOnScreen(viewportPosition)
                        if (renderer.top > 4) { borderX = viewportPosition[0]+viewport.width/2; borderY = viewportPosition[1]+2 }
                        else if (renderer.left > 4) { borderX = viewportPosition[0]+2; borderY = viewportPosition[1]+viewport.height/2 }
                        check(session!!.toolbar.more.right <= session!!.toolbar.width)
                    }
                    val screenshot = checkNotNull(uiAutomation.takeScreenshot())
                    try {
                        val output = java.io.File(targetContext.getExternalFilesDir(null),"fit-$label.png")
                        output.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                        val expected = when(format.rotation) { 90 -> listOf(2,0,3,1); 180 -> listOf(3,2,1,0); 270 -> listOf(1,3,0,2); else -> listOf(0,1,2,3) }
                        val corners = listOf(0.025 to 0.025,0.975 to 0.025,0.025 to 0.975,0.975 to 0.975)
                        corners.forEachIndexed { index,(x,y) ->
                            val pixel = screenshot.getPixel(position[0]+(videoWidth*x).toInt(),position[1]+(videoHeight*y).toInt())
                            check(matches(pixel,expected[index])) { "Corner $index missing/cropped: $label, rgb=${Color.red(pixel)},${Color.green(pixel)},${Color.blue(pixel)}" }
                        }
                        if (borderX >= 0) {
                            val border = screenshot.getPixel(borderX,borderY)
                            check(Color.red(border) < 20 && Color.green(border) < 20 && Color.blue(border) < 20) { "Letterbox not black: $label" }
                        }
                        passed++
                    } finally { screenshot.recycle() }
                }
            }
        } catch (e: Exception) { failure = e }
        finally { runOnMainSync { session?.close(); activity?.finish() } }
        report.putInt("passed",passed)
        if (failure == null) {
            report.putString("result","PASS: 36 cases; all four corner pixels visible, correct aspect ratio, contained bounds and black letterbox; dynamic resolution and 0/90/180/270 rotation")
            finish(Activity.RESULT_OK,report)
        } else { report.putString("result","FAIL: ${failure.message}"); finish(Activity.RESULT_CANCELED,report) }
    }
}
