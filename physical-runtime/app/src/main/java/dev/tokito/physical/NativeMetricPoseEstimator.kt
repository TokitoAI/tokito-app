package dev.tokito.physical

import ai.tokito.physical.ar.*
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import kotlin.math.hypot
import kotlin.math.sqrt

/** Refines a planar metric pose; unchanged pixel-error gates reject poor fits. */
class NativeMetricPoseEstimator(private val validityNanos: Long) {
    fun estimate(board: List<Point2>, image: List<Point2>, k: CameraIntrinsics,
        revision: Long, stamp: Long): PoseEstimation {
        if (board.size != image.size || board.size < 4 || !OpenCVLoader.initLocal())
            return PoseEstimation.Rejected("Metric pose requires matched planar landmarks")
        val objectPoints = MatOfPoint3f(*board.map { Point3(it.x,it.y,0.0) }.toTypedArray())
        val imagePoints = MatOfPoint2f(*image.map { Point(it.x,it.y) }.toTypedArray())
        val camera = Mat.eye(3,3,CvType.CV_64F)
        val distortion = MatOfDouble(0.0,0.0,0.0,0.0)
        val rotation = Mat(); val translation = Mat(); val matrix = Mat(); val projected = MatOfPoint2f()
        try {
            camera.put(0,0,k.fx); camera.put(1,1,k.fy); camera.put(0,2,k.cx); camera.put(1,2,k.cy)
            if (!Calib3d.solvePnP(objectPoints,imagePoints,camera,distortion,rotation,translation,false,Calib3d.SOLVEPNP_ITERATIVE))
                return PoseEstimation.Rejected("Planar pose could not be solved")
            Calib3d.solvePnPRefineLM(objectPoints,imagePoints,camera,distortion,rotation,translation)
            Calib3d.Rodrigues(rotation,matrix)
            Calib3d.projectPoints(objectPoints,rotation,translation,camera,distortion,projected)
            val p=projected.toArray()
            val rms=sqrt(p.indices.sumOf { i -> val dx=p[i].x-image[i].x; val dy=p[i].y-image[i].y; dx*dx+dy*dy }/p.size)
            if (!rms.isFinite() || rms>8.0 || rms/hypot(k.widthPx.toDouble(),k.heightPx.toDouble())>0.005)
                return PoseEstimation.Rejected("Refined pose exceeds reprojection error bound")
            fun column(c:Int)=Vector3(matrix.get(0,c)[0],matrix.get(1,c)[0],matrix.get(2,c)[0])
            val t=Vector3(translation.get(0,0)[0],translation.get(1,0)[0],translation.get(2,0)[0])
            if (t.z<=0 || !listOf(t.x,t.y,t.z).all(Double::isFinite))
                return PoseEstimation.Rejected("Board pose is behind the camera")
            return PoseEstimation.Accepted(MetricBoardPose(column(0),column(1),column(2),t,revision,stamp,
                stamp+validityNanos,rms,(1-rms/8.0).coerceIn(0.0,1.0)))
        } catch (failure: CvException) {
            return PoseEstimation.Rejected("Planar pose optimization failed")
        } finally {
            objectPoints.release(); imagePoints.release(); camera.release(); distortion.release()
            rotation.release(); translation.release(); matrix.release(); projected.release()
        }
    }
}
