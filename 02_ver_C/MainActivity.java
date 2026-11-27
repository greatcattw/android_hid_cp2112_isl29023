package com.example.isl29023c;
//package com.example.cp2112_eeprom_04;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashMap;

import android.widget.TextView;
import android.widget.ScrollView;

public class MainActivity extends AppCompatActivity {

    private static final int CP2112_VENDOR_ID = 0x10C4;
    private static final int CP2112_PRODUCT_ID = 0xEA90;

    private UsbManager usbManager;

    private TextView tvValue;
    private TextView tvLog;
    private ScrollView scrollLog;


    private UsbDevice cp2112UsbDevice;
    private Cp2112Device cp2112Device;
    private ISL29023 isl29023;

    private boolean isInitialized = false;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        tvValue = findViewById(R.id.tvValue);
        tvLog = findViewById(R.id.tvLog);
        scrollLog = findViewById(R.id.scrollLog);

        showValue("請按 Init 初始化 CP2112");
        clearLog();
        appendLog("請按 Init 初始化 CP2112");
    }

    // ============================================================
    // Init Button onClick
    // XML:
    // android:onClick="btn_init"
    // ============================================================

    public void btn_init(View v) {
        clearLog();
        showValue("初始化中...");
        appendLog("========== Init ==========");

        new Thread(() -> {
            closeCurrentDevice();

            cp2112UsbDevice = findCp2112Device();

            if (cp2112UsbDevice == null) {
                showValue("錯誤: 未找到 CP2112 裝置");
                return;
            }

            log(String.format(
                    "找到 CP2112: VID=0x%04X, PID=0x%04X",
                    cp2112UsbDevice.getVendorId(),
                    cp2112UsbDevice.getProductId()
            ));

            if (!usbManager.hasPermission(cp2112UsbDevice)) {
                requestUsbPermission(cp2112UsbDevice);
                showValue("請允許 USB 權限後，再按一次 Init");
                return;
            }

            cp2112Device = new Cp2112Device(
                    usbManager,
                    cp2112UsbDevice,
                    this::log
            );

            boolean openOk = cp2112Device.open();

            if (!openOk) {
                isInitialized = false;
                showValue("錯誤: CP2112 初始化失敗");
                closeCurrentDevice();
                return;
            }

            isl29023 = new ISL29023(
                    cp2112Device,
                    this::log
            );

            /*
             * 新增:
             * CP2112 初始化成功後，設定 ISL29023 連續轉換亮度模式。
             *
             * Register 0x00 = 0xA0
             */
            boolean rangeOk;
            boolean sensorInitOk;

            try {


                /*
                 * 再設定 ISL29023 連續轉換亮度模式。
                 *
                 * Register 0x00 = 0xA0
                 */
                sensorInitOk = isl29023.initContinuousAlsMode();

                Thread.sleep(10);

                /*
                 * 先設定 ISL29023 最大亮度範圍為 4000 lux。
                 *
                 * Register 0x01 = 0x01
                 */
                rangeOk = isl29023.setRange4000Lux();

//                if (!rangeOk) {
//                    showValue("錯誤: ISL29023 設定 4000 lux 範圍失敗");
//                    closeCurrentDevice();
//                    return;
//                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                showValue("ISL29023 初始化被中斷");
                closeCurrentDevice();
                return;
            } catch (Exception e) {
                showValue("ISL29023 初始化異常: " + e.getMessage());
                closeCurrentDevice();
                return;
            }

            if (!sensorInitOk) {
                showValue("錯誤: ISL29023 初始化失敗");
                closeCurrentDevice();
                return;
            }

            isInitialized = true;

            showValue("CP2112 / ISL29023 初始化完成\nRange: 4000 lux");



        }).start();
    }


    // ============================================================
    // Read Button onClick
    // XML:
    // android:onClick="btn_read"
    // ============================================================

    public void btn_read(View v) {
        clearLog();
        appendLog("========== Read ==========");

        if (!checkInitialized()) {
            return;
        }

        showValue("讀取中...");

        new Thread(() -> {
            try {
                Integer rawValue = isl29023.readRawWord();

                if (rawValue == null) {
                    showValue("讀取失敗或逾時");
                    return;
                }

//                showValue(String.format(
//                        "ISL29023 Raw: 0x%04X\n %d",
//                        rawValue,
//                        ( rawValue *1000 ) / 65535
//                ));
                double lux = rawValue * 4000.0 / 65536.0;

                showValue(String.format(
                        "Lux: %.2f lx\n"+"0x%04X",
                        lux,
                        rawValue
                ));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                showValue("讀取被中斷: " + e.getMessage());
            } catch (Exception e) {
                showValue("讀取異常: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    // ============================================================
    // 尋找 CP2112 USB Device
    // ============================================================

    private UsbDevice findCp2112Device() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();

        for (UsbDevice device : deviceList.values()) {
            if (device.getVendorId() == CP2112_VENDOR_ID &&
                    device.getProductId() == CP2112_PRODUCT_ID) {
                return device;
            }
        }

        return null;
    }

    // ============================================================
    // 要求 USB 權限
    // ============================================================

    private void requestUsbPermission(UsbDevice device) {
        PendingIntent permissionIntent = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE
        );

        usbManager.requestPermission(device, permissionIntent);
    }

    // ============================================================
    // 檢查是否已初始化
    // ============================================================

    private boolean checkInitialized() {
        if (!isInitialized || cp2112Device == null || isl29023 == null) {
            showValue("錯誤: 尚未初始化，請先按 Init");
            return false;
        }

        if (!cp2112Device.isOpened()) {
            showValue("錯誤: CP2112 尚未開啟，請重新按 Init");
            isInitialized = false;
            return false;
        }

        return true;
    }

    // ============================================================
    // 關閉目前 CP2112
    // ============================================================

    private void closeCurrentDevice() {
        try {
            if (cp2112Device != null) {
                cp2112Device.close();
            }
        } catch (Exception e) {
            log("關閉 CP2112 發生異常: " + e.getMessage());
        }

        cp2112Device = null;
        isl29023 = null;
        cp2112UsbDevice = null;
        isInitialized = false;
    }

    // ============================================================
    // Activity 結束時釋放 USB
    // ============================================================

    @Override
    protected void onDestroy() {
        closeCurrentDevice();
        super.onDestroy();
    }

    // ============================================================
    // UI 顯示
    // ============================================================

    private void showValue(String message) {
        runOnUiThread(() -> tvValue.setText(message));
    }

    // ============================================================
    // Log
    //
    // 目前畫面只保留數值 TextView，所以 log 先輸出到 Logcat。
    // 如果你想在畫面上也顯示 log，可以再加一個 TextView。
    // ============================================================

    private void clearLog() {
        runOnUiThread(() -> {
            if (tvLog != null) {
                tvLog.setText("");
            }
        });
    }

    private void appendLog(String message) {
        runOnUiThread(() -> {
            if (tvLog != null) {
                tvLog.append(message + "\n");
            }

            if (scrollLog != null) {
                scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void log(String message) {
        android.util.Log.d("CP2112_ISL29023", message);
        appendLog(message);
    }
}
