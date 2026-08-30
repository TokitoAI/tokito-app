package ai.tokito.physical.ar

/** Maps calibrated active-array intrinsics into a FILL_CENTER preview. */
object CameraCalibrationMapper {
    fun toPreview(
        sensorFx: Double,
        sensorFy: Double,
        sensorCx: Double,
        sensorCy: Double,
        sensorWidth: Int,
        sensorHeight: Int,
        clockwiseRotationDegrees: Int,
        previewWidth: Int,
        previewHeight: Int,
    ): CameraIntrinsics? {
        if (sensorWidth <= 0 || sensorHeight <= 0 || previewWidth <= 0 || previewHeight <= 0 ||
            listOf(sensorFx, sensorFy, sensorCx, sensorCy).any { !it.isFinite() } || sensorFx <= 0 || sensorFy <= 0
        ) return null
        val rotation = ((clockwiseRotationDegrees % 360) + 360) % 360
        val rotated = when (rotation) {
            0 -> doubleArrayOf(sensorFx, sensorFy, sensorCx, sensorCy, sensorWidth.toDouble(), sensorHeight.toDouble())
            90 -> doubleArrayOf(sensorFy, sensorFx, sensorHeight - sensorCy, sensorCx, sensorHeight.toDouble(), sensorWidth.toDouble())
            180 -> doubleArrayOf(sensorFx, sensorFy, sensorWidth - sensorCx, sensorHeight - sensorCy, sensorWidth.toDouble(), sensorHeight.toDouble())
            270 -> doubleArrayOf(sensorFy, sensorFx, sensorCy, sensorWidth - sensorCx, sensorHeight.toDouble(), sensorWidth.toDouble())
            else -> return null
        }
        val scale = maxOf(previewWidth / rotated[4], previewHeight / rotated[5])
        val offsetX = (previewWidth - rotated[4] * scale) / 2.0
        val offsetY = (previewHeight - rotated[5] * scale) / 2.0
        return CameraIntrinsics(
            fx = rotated[0] * scale,
            fy = rotated[1] * scale,
            cx = rotated[2] * scale + offsetX,
            cy = rotated[3] * scale + offsetY,
            widthPx = previewWidth,
            heightPx = previewHeight,
        )
    }
}
