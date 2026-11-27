package com.example.isl29023a;

public class ISL29023 {

    // ============================================================
    // ISL29023 I2C Address
    // ============================================================

    /*
     * ISL29023 常見 7-bit I2C address = 0x44
     * CP2112 Report 中使用 left-shift 1 bit address
     *
     * 0x44 << 1 = 0x88
     */
    private static final byte ISL29023_I2C_ADDRESS_SHIFTED = (byte) (0x44 << 1);

    /*
     * ISL29023 Data Register
     *
     * Register 0x02: Data LSB
     * Register 0x03: Data MSB
     */
    private static final byte REG_COMMAND_I = 0x00;
    private static final byte REG_COMMAND_II = 0x01;
    private static final byte REG_DATA_LSB = 0x02;
    /*
     * Command Register I
     * 0xA0: ALS continuous conversion mode
     */
    private static final byte COMMAND_I_ALS_CONTINUOUS = (byte) 0xA0;
    /*
     * Command Register II
     *
     * Range bits:
     * 00 = 1000 lux
     * 01 = 4000 lux
     * 10 = 16000 lux
     * 11 = 64000 lux
     *
     * 0x01:
     * Range = 4000 lux
     * ADC resolution bits 維持 00，通常代表 16-bit。
     */
    private static final byte COMMAND_II_RANGE_4000_LUX = 0x01;
    // ============================================================
    // CP2112 Report ID
    // ============================================================

    private static final byte REPORT_ID_DATA_WRITE_READ_REQUEST = 0x11;
    private static final byte REPORT_ID_DATA_READ_REQUEST = 0x12;
    private static final byte REPORT_ID_DATA_READ_RESPONSE = 0x13;
    private static final byte REPORT_ID_DATA_WRITE = 0x14;

    // ============================================================
    // CP2112 Transfer Status
    // ============================================================

    private static final byte TRANSFER_STATUS_COMPLETE = 0x02;

    private final Cp2112Device cp2112;
    private final Cp2112Device.LogCallback logger;

    public ISL29023(
            Cp2112Device cp2112,
            Cp2112Device.LogCallback logger
    ) {
        this.cp2112 = cp2112;
        this.logger = logger;
    }

    // ============================================================
    // 讀取 ISL29023 Register 0x02 / 0x03，組成 16-bit word
    // ============================================================

    public Integer readRawWord() throws InterruptedException {
        log("【ISL29023】開始讀取 Register 0x02 / 0x03...");

        /*
         * Step 1:
         * 發送 0x11 Data Write Read Request
         * 指定從 register 0x02 開始，連續讀取 2 bytes。
         */
        boolean requestOk = sendWriteReadRequest(REG_DATA_LSB, 2);

        if (!requestOk) {
            log("錯誤: ISL29023 Data Write Read Request 送出失敗。");
            return null;
        }

        /*
         * Step 2:
         * 對 CP2112 發送 0x12 Data Read Request，
         * 要求 CP2112 把 read buffer 裡面的 2 bytes 回傳。
         *
         * 有些 CP2112 / Android HID 環境需要稍微等待。
         */
        Thread.sleep(10);

        /*
         * Step 3:
         * 反覆送 0x12 並等待 0x13 回應正確。
         */
        return loopReadResponseForWord();
    }

    // ============================================================
    // 0x11: Data Write Read Request
    // ============================================================

    private boolean sendWriteReadRequest(byte registerAddress, int readLength) {
        log(String.format(
                "【ISL29023】發送 0x11，Register=0x%02X, ReadLength=%d",
                registerAddress,
                readLength
        ));

        byte[] packet = new byte[Cp2112Device.PACKET_SIZE];

        /*
         * CP2112 Data Write Read Request
         *
         * Byte 0: Report ID = 0x11
         * Byte 1: Slave Address = 0x88
         * Byte 2: Read Length MSB
         * Byte 3: Read Length LSB
         * Byte 4: Target Address Length
         * Byte 5: Target Register Address
         */
        packet[0] = REPORT_ID_DATA_WRITE_READ_REQUEST;
        packet[1] = ISL29023_I2C_ADDRESS_SHIFTED;
        packet[2] = (byte) ((readLength >> 8) & 0xFF);
        packet[3] = (byte) (readLength & 0xFF);
        packet[4] = 0x01;
        packet[5] = registerAddress;

        boolean ok = cp2112.sendPacket(packet);

        if (!ok) {
            log("錯誤: Data Write Read Request 0x11 送出失敗。");
            return false;
        }

        return true;
    }

    // ============================================================
    // 0x12: Data Read Request
    // ============================================================

    private boolean sendDataReadRequest(int readLength) {
        log("【ISL29023】發送 Data Read Request 0x12...");

        byte[] packet = new byte[Cp2112Device.PACKET_SIZE];

        /*
         * CP2112 Data Read Request
         *
         * 延續你原本 AT24C02 可用的 Android 格式：
         *
         * Byte 0: Report ID = 0x12
         * Byte 1: Slave Address
         * Byte 2: Read Length MSB
         * Byte 3: Read Length LSB
         */
        packet[0] = REPORT_ID_DATA_READ_REQUEST;
        packet[1] = ISL29023_I2C_ADDRESS_SHIFTED;
        packet[2] = (byte) ((readLength >> 8) & 0xFF);
        packet[3] = (byte) (readLength & 0xFF);

        boolean ok = cp2112.sendPacket(packet);

        if (!ok) {
            log("錯誤: Data Read Request 0x12 送出失敗。");
            return false;
        }

        return true;
    }

    // ============================================================
    // 0x13: Data Read Response
    // ============================================================

    private Integer loopReadResponseForWord() throws InterruptedException {
        byte[] buffer = null;

        final int maxRetry = 100;
        int retry = 0;

        log("【ISL29023】等待 Data Read Response 0x13...");

        while (retry < maxRetry) {

            boolean readRequestOk = sendDataReadRequest(2);

            if (!readRequestOk) {
                log("錯誤: Data Read Request 0x12 送出失敗。");
                return null;
            }

            buffer = cp2112.receivePacket();

            if (buffer == null) {
                log("錯誤: 未收到 CP2112 Data Read Response。");
                return null;
            }

            byte reportId = buffer[0];
            byte status = buffer[1];
            int length = buffer[2] & 0xFF;

            log(String.format(
                    "Read Response Retry=%d, ReportID=0x%02X, Status=0x%02X, Length=%d",
                    retry + 1,
                    reportId,
                    status,
                    length
            ));

            if (reportId == REPORT_ID_DATA_READ_RESPONSE &&
                    status == TRANSFER_STATUS_COMPLETE &&
                    length >= 2) {

                int lsb = buffer[3] & 0xFF;
                int msb = buffer[4] & 0xFF;

                int rawWord = (msb << 8) | lsb;

                log(String.format(
                        "ISL29023 Raw Data: LSB=0x%02X, MSB=0x%02X, WORD=0x%04X, DEC=%d",
                        lsb,
                        msb,
                        rawWord,
                        rawWord
                ));

                return rawWord;
            }

            Thread.sleep(10);
            retry++;
        }

        log(String.format(
                "錯誤: ISL29023 讀取逾時，重試 %d 次仍未收到正確資料。",
                maxRetry
        ));

        if (buffer != null) {
            log(String.format(
                    "最後一次 Response: ReportID=0x%02X, Status=0x%02X, Length=%d",
                    buffer[0],
                    buffer[1],
                    buffer[2] & 0xFF
            ));
        }

        return null;
    }

// ============================================================
// 設定 ISL29023 最大亮度範圍為 4000 lux
// ============================================================

    public boolean setRange4000Lux() throws InterruptedException {
        log("【ISL29023】設定最大亮度範圍為 4000 lux...");
        log("【ISL29023】寫入 Register 0x01 = 0x01");

        boolean writeOk = writeRegister(
                REG_COMMAND_II,
                COMMAND_II_RANGE_4000_LUX
        );

        if (!writeOk) {
            log("錯誤: ISL29023 Command Register 0x01 寫入 0x01 失敗。");
            return false;
        }

        Thread.sleep(10);

        log("ISL29023 最大亮度範圍已設定為 4000 lux。");
        return true;
    }

    // ============================================================
    // 初始化 ISL29023：設定為連續轉換 ALS 模式
    // ============================================================

    public boolean initContinuousAlsMode() throws InterruptedException {
        log("【ISL29023】初始化: 設定 ALS Continuous Mode...");
        log("【ISL29023】寫入 Register 0x00 = 0xA0");

        boolean writeOk = writeRegister(
                REG_COMMAND_I,
                COMMAND_I_ALS_CONTINUOUS
        );

        if (!writeOk) {
            log("錯誤: ISL29023 Command Register 0x00 寫入 0xA0 失敗。");
            return false;
        }

        /*
         * 寫入 command 後稍微等一下，讓 sensor 進入轉換狀態。
         */
        Thread.sleep(100);

        log("ISL29023 已設定為連續轉換亮度模式。");
        return true;
    }

    // ============================================================
    // 寫入 ISL29023 Register
    // ============================================================

    private boolean writeRegister(byte registerAddress, byte data) throws InterruptedException {
        log(String.format(
                "【ISL29023 寫入】Register=0x%02X, Data=0x%02X",
                registerAddress,
                data
        ));

        byte[] packet = new byte[Cp2112Device.PACKET_SIZE];

        /*
         * CP2112 Data Write Report
         *
         * Byte 0: Report ID = 0x14
         * Byte 1: Slave Address = 0x88
         * Byte 2: Length LSB
         * Byte 3: Register Address
         * Byte 4: Data
         *
         * Length = 2，表示後面有 2 bytes:
         * Register Address + Data
         */
        packet[0] = REPORT_ID_DATA_WRITE;
        packet[1] = ISL29023_I2C_ADDRESS_SHIFTED;
        packet[2] = 0x02;
        packet[3] = registerAddress;
        packet[4] = data;

        boolean ok = cp2112.sendPacket(packet);

        if (!ok) {
            log("錯誤: ISL29023 寫入封包 0x14 送出失敗。");
            return false;
        }
//
//        /*
//         * 你的 CP2112 需要用 0x15 / 0x16 確認傳輸完成。
//         */
//        boolean transferOk = waitTransferComplete();
//
//        if (!transferOk) {
//            log("錯誤: ISL29023 Register 寫入後，CP2112 Transfer 未完成。");
//            return false;
//        }

        log("ISL29023 Register 寫入完成。");
        return true;
    }
    private void log(String message) {
        if (logger != null) {
            logger.log(message);
        }
    }
}
