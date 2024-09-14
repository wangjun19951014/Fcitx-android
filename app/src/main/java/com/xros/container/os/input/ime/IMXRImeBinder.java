package com.xros.container.os.input.ime;

public interface IMXRImeBinder {
    String DESCRIPTOR = "com.xros.container.os.input.ime.IMXRImeManager";
    int MXR_IME_INVALID_DISPLAY_ID = -1;
    int MXR_IME_TRANSACTION_FIRST_CALL = 30000;
    int MXR_IME_TRANSACTION_END_CALL = 30100;

    int MXR_IME_TRANSACTION_registerClientCallback = MXR_IME_TRANSACTION_FIRST_CALL + 1;
    int MXR_IME_TRANSACTION_SendClientWindowStatus = MXR_IME_TRANSACTION_FIRST_CALL + 2;
    int MXR_IME_TRANSACTION_GetImeDisplay = MXR_IME_TRANSACTION_FIRST_CALL + 3;
}
