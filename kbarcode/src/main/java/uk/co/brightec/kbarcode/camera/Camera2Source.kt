package uk.co.brightec.kbarcode.camera

import android.content.Context
import android.content.Context.CAMERA_SERVICE
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission
import androidx.annotation.VisibleForTesting
import timber.log.Timber

internal class Camera2Source(
    private val cameraManager: CameraManager
) {

    @VisibleForTesting
    internal var cameraDevice: CameraDevice? = null
    var requestedFacing = CameraCharacteristics.LENS_FACING_BACK

    constructor(context: Context) : this(
        cameraManager = context.getSystemService(CAMERA_SERVICE) as CameraManager
    )

    fun isStarted() = cameraDevice != null

    fun release() {
        cameraDevice?.close()
        cameraDevice = null
    }

    @RequiresPermission(android.Manifest.permission.CAMERA)
    fun start(
        surfaces: List<Surface>,
        listener: OnCameraReadyListener?
    ) {
        if (cameraDevice != null) return

        val cameraId = selectCamera()
        if (cameraId == null) {
            val exception = CameraServiceException()
            Timber.e(exception, "Error opening camera - No cameraId available")
            listener?.onCameraFailure(exception)
            return
        }
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                listener?.onCameraReady()
                createCaptureSession(
                    surfaces = surfaces,
                    listener = listener
                )
            }

            override fun onDisconnected(camera: CameraDevice) {
                release()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                release()
                val exception = createExceptionFromCameraDeviceError(error)
                Timber.e(exception, "Error opening camera")
                listener?.onCameraFailure(exception)
            }
        }, null)
    }

    fun getOutputSize(minWidth: Int): Size? {
        val cameraDevice = this.cameraDevice
        // If we already have a camera started use that, otherwise use the camera id of the
        // camera we expect to start
        val characteristics = if (cameraDevice != null) {
            cameraManager.getCameraCharacteristics(cameraDevice.id)
        } else {
            val cameraId = selectCamera() ?: return null
            cameraManager.getCameraCharacteristics(cameraId)
        }
        val configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException() // This key is available on all devices
        val sizes = configs.getOutputSizes(IMAGE_FORMAT)
        return chooseOutputSize(sizes, minWidth)
    }

    fun getCameraFacing(): Int? {
        val cameraDevice = this.cameraDevice ?: return null
        val characteristics = cameraManager.getCameraCharacteristics(cameraDevice.id)
        return characteristics.get(CameraCharacteristics.LENS_FACING)
    }

    fun getCameraSensorOrientation(): Int? {
        val cameraDevice = this.cameraDevice ?: return null
        val characteristics = cameraManager.getCameraCharacteristics(cameraDevice.id)
        return characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
    }

    @Suppress("ReturnCount") // Better readability
    @VisibleForTesting
    internal fun selectCamera(): String? {
        if (cameraManager.cameraIdList.isEmpty()) return null

        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == requestedFacing) {
                return cameraId
            }
        }
        return cameraManager.cameraIdList[0]
    }

    @VisibleForTesting
    internal fun createCaptureSession(
        surfaces: List<Surface>,
        listener: OnCameraReadyListener?
    ) {
        try {
            cameraDevice?.createCaptureSession(
                surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        @Suppress("UnsafeCallOnNullableType")
                        val builder = cameraDevice!!.createCaptureRequest(
                            CameraDevice.TEMPLATE_PREVIEW
                        )
                        val autoFocus = selectBestAutoFocus()
                        if (autoFocus != null) {
                            builder.set(CaptureRequest.CONTROL_AF_MODE, autoFocus)
                        }
                        for (surface in surfaces) {
                            builder.addTarget(surface)
                        }
                        session.setRepeatingRequest(builder.build(), null, null)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        release()
                        val exception = CameraSessionException()
                        Timber.e(exception, "Error creating camera session")
                        listener?.onCameraFailure(exception)
                    }
                },
                null
            )
        } catch (e: android.hardware.camera2.CameraAccessException) {
            release()
            val exception = CameraAccessException()
            Timber.e(exception, "Error creating camera session")
            listener?.onCameraFailure(exception)
        }
    }

    /**
     * Select the smallest pixel width size which is still larger than minWidth
     *
     * Fall back: Largest size (if none large enough), or just first element
     */
    @Suppress("MagicNumber")
    @VisibleForTesting
    internal fun chooseOutputSize(sizes: Array<Size>, minWidth: Int): Size {
        val largeEnoughSizes = sizes.filter { it.width > minWidth }
        return if (largeEnoughSizes.isNotEmpty()) {
            largeEnoughSizes.minBy { it.width }
        } else {
            sizes.maxBy { it.width }
        } ?: sizes[0]
    }

    @VisibleForTesting
    internal fun createExceptionFromCameraDeviceError(error: Int) = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> CameraInUseException()
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> MaxCamerasInUseException()
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> CameraDisabledException()
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> CameraDeviceException()
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> CameraServiceException()
        else -> CameraException()
    }

    @Suppress("ReturnCount") // For readability
    @VisibleForTesting
    internal fun selectBestAutoFocus(): Int? {
        val cameraDevice = this.cameraDevice ?: return null
        val characteristics = cameraManager.getCameraCharacteristics(cameraDevice.id)
        val available = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?: throw IllegalStateException() // This key is available on all devices
        return when {
            available.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            available.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) ->
                CameraMetadata.CONTROL_AF_MODE_AUTO
            else ->
                CameraMetadata.CONTROL_AF_MODE_OFF
        }
    }

    companion object {

        const val IMAGE_FORMAT = ImageFormat.YUV_420_888
    }
}
