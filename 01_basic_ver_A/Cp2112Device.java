package com.example.isl29023a;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

public class Cp2112Device {

    public interface LogCallback {
        void log(String message);
    }

    public static final int PACKET_SIZE = 64;
    private static final int USB_TIMEOUT_MS = 1000;

    // ============================================================
    // CP2112 Report ID
    // ============================================================

    // SMBus / I2C 設定用 Report
    private static final byte REPORT_ID_SMBUS_CONFIG = 0x10;

    public static final byte REPORT_ID_DATA_WRITE = 0x14;
    public static final byte REPORT_ID_DATA_WRITE_READ_REQUEST = 0x11;
    public static final byte REPORT_ID_DATA_READ_REQUEST = 0x12;
    public static final byte REPORT_ID_DATA_READ_RESPONSE = 0x13;

    private final UsbManager usbManager;
    private final UsbDevice usbDevice;
    private final LogCallback logger;

    private UsbDeviceConnection connection;
    private UsbInterface usbInterface;
    private UsbEndpoint endpointIn;
    private UsbEndpoint endpointOut;

    public Cp2112Device(
            UsbManager usbManager,
            UsbDevice usbDevice,
            LogCallback logger
    ) {
        this.usbManager = usbManager;
        this.usbDevice = usbDevice;
        this.logger = logger;
    }

    public boolean open() {
        connection = usbManager.openDevice(usbDevice);

        if (connection == null) {
            log("錯誤: 無法開啟 USB 連線。");
            return false;
        }

        usbInterface = usbDevice.getInterface(0);

        if (!connection.claimInterface(usbInterface, true)) {
            log("錯誤: 無法獨佔 USB 介面。");
            close();
            return false;
        }

        if (!findEndpoints()) {
            log("錯誤: 找不到 CP2112 的 IN / OUT Endpoint。");
            close();
            return false;
        }

        log("CP2112 Endpoint 初始化完成。");
        log("IN Endpoint: " + endpointIn);
        log("OUT Endpoint: " + endpointOut);

        // ========================================================
        // 設定 CP2112 I2C / SMBus Clock = 100 kHz
        // ========================================================
        if (!configureI2cClock100kHz()) {
            log("警告: CP2112 I2C Clock 設定失敗。");
        } else {
            log("CP2112 I2C Clock 已設定為 100 kHz。");
        }

        return true;
    }

    public void close() {
        try {
            if (connection != null && usbInterface != null) {
                connection.releaseInterface(usbInterface);
            }
        } catch (Exception e) {
            log("釋放 USB Interface 發生異常: " + e.getMessage());
        }

        try {
            if (connection != null) {
                connection.close();
            }
        } catch (Exception e) {
            log("關閉 USB Connection 發生異常: " + e.getMessage());
        }

        connection = null;
        usbInterface = null;
        endpointIn = null;
        endpointOut = null;
    }

    // ============================================================
    // 設定 CP2112 I2C Clock 為 100 kHz
    // ============================================================

    public boolean configureI2cClock100kHz() {
        if (!isOpened()) {
            log("錯誤: CP2112 尚未開啟，無法設定 I2C Clock。");
            return false;
        }

        log("設定 CP2112 I2C Clock 為 100 kHz...");

        byte[] packet = new byte[PACKET_SIZE];

        /*
         * 你測試過可用的參數：
         *
         * [0x10, 0x00, 0x01, 0x03, 0x0A, 0x00, 0x00, 0x00, 0x00, 0x00]
         *
         * 對應填入 CP2112 64-byte HID packet 前 10 bytes。
         */
        packet[0] = REPORT_ID_SMBUS_CONFIG; // 0x10
        packet[1] = 0x00;
        packet[2] = 0x01;
        packet[3] = 0x03;
        packet[4] = 0x0A;
        packet[5] = 0x00;
        packet[6] = 0x00;
        packet[7] = 0x00;
        packet[8] = 0x00;
        packet[9] = 0x00;

        int result = connection.bulkTransfer(
                endpointOut,
                packet,
                packet.length,
                USB_TIMEOUT_MS
        );

        if (result < 0) {
            log("錯誤: CP2112 I2C Clock 設定封包送出失敗。");
            return false;
        }

        log(String.format(
                "CP2112 I2C Clock 設定封包已送出，USB OUT result=%d",
                result
        ));

        return true;
    }

    // ============================================================
    // 傳送 CP2112 封包
    // ============================================================

    public boolean sendPacket(byte[] packet) {
        if (!isOpened()) {
            log("錯誤: CP2112 尚未開啟，無法送出封包。");
            return false;
        }

        if (packet == null || packet.length != PACKET_SIZE) {
            log("錯誤: CP2112 封包長度必須是 64 bytes。");
            return false;
        }

        int result = connection.bulkTransfer(
                endpointOut,
                packet,
                packet.length,
                USB_TIMEOUT_MS
        );

        if (result < 0) {
            log("錯誤: USB OUT 傳輸失敗。");
            return false;
        }

        return true;
    }

    // ============================================================
    // 接收 CP2112 封包
    // ============================================================

    public byte[] receivePacket() {
        if (!isOpened()) {
            log("錯誤: CP2112 尚未開啟，無法接收封包。");
            return null;
        }

        byte[] buffer = new byte[PACKET_SIZE];

        int result = connection.bulkTransfer(
                endpointIn,
                buffer,
                buffer.length,
                USB_TIMEOUT_MS
        );

        if (result <= 0) {
            log("錯誤: USB IN 沒有收到資料。");
            return null;
        }

        return buffer;
    }

    public boolean isOpened() {
        return connection != null &&
                usbInterface != null &&
                endpointIn != null &&
                endpointOut != null;
    }

    private boolean findEndpoints() {
        endpointIn = null;
        endpointOut = null;

        for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
            UsbEndpoint endpoint = usbInterface.getEndpoint(i);

            if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                endpointIn = endpoint;
            } else if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                endpointOut = endpoint;
            }
        }

        return endpointIn != null && endpointOut != null;
    }

    private void log(String message) {
        if (logger != null) {
            logger.log(message);
        }
    }
}
