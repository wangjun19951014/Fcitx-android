/*
 * This file is auto-generated.  DO NOT MODIFY.
 */
package com.xros.container.os.input.ime;
public interface MXRImeClientCallback extends android.os.IInterface
{
  /** Default implementation for MXRImeClientCallback. */
  public static class Default implements MXRImeClientCallback
  {
    @Override public void setImeDisplay(int displayId) throws android.os.RemoteException
    {
    }
    @Override public void setImeDisplayStatus(boolean show) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements MXRImeClientCallback
  {
    private static final String DESCRIPTOR = "com.xros.container.os.input.ime.MXRImeClientCallback";
    /** Construct the stub at attach it to the interface. */
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.xros.container.os.input.ime.MXRImeClientCallback interface,
     * generating a proxy if needed.
     */
    public static MXRImeClientCallback asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof MXRImeClientCallback))) {
        return ((MXRImeClientCallback)iin);
      }
      return new Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      String descriptor = DESCRIPTOR;
      switch (code)
      {
        case INTERFACE_TRANSACTION:
        {
          reply.writeString(descriptor);
          return true;
        }
        case TRANSACTION_setImeDisplay:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          this.setImeDisplay(_arg0);
          reply.writeNoException();
          return true;
        }
        case TRANSACTION_setImeWindowStatus:
        {
          data.enforceInterface(descriptor);
          boolean _arg0;
          _arg0 = (0!=data.readInt());
          this.setImeDisplayStatus(_arg0);
          reply.writeNoException();
          return true;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
    }
    private static class Proxy implements MXRImeClientCallback
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      @Override public void setImeDisplay(int displayId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setImeDisplay, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setImeDisplay(displayId);
            return;
          }
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void setImeDisplayStatus(boolean show) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(((show)?(1):(0)));
          boolean _status = mRemote.transact(Stub.TRANSACTION_setImeWindowStatus, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setImeDisplayStatus(show);
            return;
          }
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      public static MXRImeClientCallback sDefaultImpl;
    }
    static final int TRANSACTION_setImeDisplay = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_setImeWindowStatus = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    public static boolean setDefaultImpl(MXRImeClientCallback impl) {
      // Only one user of this interface can use this function
      // at a time. This is a heuristic to detect if two different
      // users in the same process use this function.
      if (Proxy.sDefaultImpl != null) {
        throw new IllegalStateException("setDefaultImpl() called twice");
      }
      if (impl != null) {
        Proxy.sDefaultImpl = impl;
        return true;
      }
      return false;
    }
    public static MXRImeClientCallback getDefaultImpl() {
      return Proxy.sDefaultImpl;
    }
  }
  public void setImeDisplay(int displayId) throws android.os.RemoteException;
  public void setImeDisplayStatus(boolean show) throws android.os.RemoteException;
}
