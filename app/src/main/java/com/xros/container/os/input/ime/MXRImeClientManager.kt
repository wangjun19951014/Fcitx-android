package com.xros.container.os.input.ime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import androidx.annotation.RequiresApi
import org.fcitx.fcitx5.android.input.FcitxInputMethodServiceBridge
import timber.log.Timber

class MXRImeClientManager(val context: Context,
    val bridge: FcitxInputMethodServiceBridge) {

    private var xrContainer: IBinder? = null
    private var serviceConn: ServiceConnection? = null

     fun connectXRContainer() {
        if (xrContainer == null) {
            var ineten: Intent = Intent()
            serviceConn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    Timber.i("MXRImeClientManager: XrContainer Ime service connected");
                    if (service != null) {
                        xrContainer = service
                        registerClientCallback()
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    Timber.i("MXRImeClientManager: XrContainer Ime service disconnected");
                    xrContainer = null
                    bridge.currentVirtualDisplayId = IMXRImeBinder.MXR_IME_INVALID_DISPLAY_ID
                }

            }
            ineten.setClassName("com.xros.container.service", "com.xros.container.os.input.ime.MXRImeManagerService")
            context.bindService(ineten, serviceConn!!, Context.BIND_AUTO_CREATE)
        }
    }

    fun registerClientCallback() {
        Timber.i("MXRImeClientManager registerClientCallback");
        if (xrContainer != null) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            data.writeInterfaceToken(IMXRImeBinder.DESCRIPTOR)
            data.writeStrongBinder(clientCallback?.asBinder())
            xrContainer!!.transact(IMXRImeBinder.MXR_IME_TRANSACTION_registerClientCallback,
                data, reply, 0)
            reply.readException()
        }
    }

    fun destroy() {
        if (serviceConn != null) {
            context.unbindService(serviceConn!!)
        }
    }


    @RequiresApi(Build.VERSION_CODES.Q)
    fun sendClientWindowStatus(show: Boolean) {
        Timber.d("MXRImeClientManager sendClientWindowStatus show?: $show")
        if (xrContainer != null) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            data.writeInterfaceToken(IMXRImeBinder.DESCRIPTOR)
            data.writeBoolean(show)
            xrContainer!!.transact(IMXRImeBinder.MXR_IME_TRANSACTION_SendClientWindowStatus,
                data, reply, 0)
            reply.readException()
        }
    }

    fun getImeDisplay(): Int {
        var display :Int = IMXRImeBinder.MXR_IME_INVALID_DISPLAY_ID
        if (xrContainer != null) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            data.writeInterfaceToken(IMXRImeBinder.DESCRIPTOR)
            xrContainer!!.transact(IMXRImeBinder.MXR_IME_TRANSACTION_GetImeDisplay,
                data, reply, 0)
            reply.readException()
            display = reply.readInt()
        }
        Timber.i("MXRImeClientManager getImeDisplay from XR Container, id?: $display")
        return display
    }

    private var clientCallback: MXRImeClientCallback = object : MXRImeClientCallback.Stub() {
        override fun setImeDisplay(displayId: Int) {
            Timber.i("MXRImeClientCallback setImeDisplay id?: $displayId")
            val currentId = bridge.currentVirtualDisplayId
            if (currentId != IMXRImeBinder.MXR_IME_INVALID_DISPLAY_ID) {
                if (displayId == IMXRImeBinder.MXR_IME_INVALID_DISPLAY_ID) {
                    bridge.handleOnRemoveVirtualDisplay()
                }
            }
            bridge.currentVirtualDisplayId = displayId
        }

        override fun setImeDisplayStatus(show: Boolean) {
            Timber.i("MXRImeClientCallback setImeDisplayStatus show?: $show")
            bridge.imeDisplayShowing = show
            if (!show) {
                //TODO if need change cursor status  when ime display is hide
            }
        }
    }
}