package com.whyun.witv.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import org.junit.Test;

public class QrCodeUtilTest {

    /** 码必须真的能被扫出来——只断言「生成了非空矩阵」没有意义。 */
    @Test
    public void encodesScannableWebAddress() throws Exception {
        String url = WebServer.buildUrl("192.168.6.133");
        BitMatrix matrix = QrCodeUtil.encodeMatrix(url, 220);
        assertNotNull(matrix);
        assertEquals(url, decode(matrix));
    }

    @Test
    public void encodesAtSmallSettingsPanelSize() throws Exception {
        String url = WebServer.buildUrl("10.0.0.2");
        assertEquals(url, decode(QrCodeUtil.encodeMatrix(url, 132)));
    }

    /** 输出是正方形，且不小于请求尺寸，否则贴到固定尺寸的 ImageView 上会被放大发虚。 */
    @Test
    public void producesSquareMatrixNoSmallerThanRequested() {
        BitMatrix matrix = QrCodeUtil.encodeMatrix("http://192.168.6.133:9979", 220);
        assertNotNull(matrix);
        assertEquals(matrix.getWidth(), matrix.getHeight());
        assertTrue(matrix.getWidth() >= 220);
    }

    /** 静区必须留白：这是贴在深色背景上的小图，没有白边很多手机扫不出来。 */
    @Test
    public void keepsQuietZoneClear() {
        BitMatrix matrix = QrCodeUtil.encodeMatrix("http://192.168.6.133:9979", 220);
        assertNotNull(matrix);
        assertFalse(matrix.get(0, 0));
        assertFalse(matrix.get(matrix.getWidth() - 1, matrix.getHeight() - 1));
    }

    /** IP 没解析出来（显示 0.0.0.0）或尺寸还没测量时返回 null，调用方据此隐藏 ImageView。 */
    @Test
    public void returnsNullOnUnusableInput() {
        assertNull(QrCodeUtil.encodeMatrix(null, 220));
        assertNull(QrCodeUtil.encodeMatrix("", 220));
        assertNull(QrCodeUtil.encodeMatrix("   ", 220));
        assertNull(QrCodeUtil.encodeMatrix("http://192.168.6.133:9979", 0));
        assertNull(QrCodeUtil.encodeMatrix("http://192.168.6.133:9979", -10));
    }

    private static String decode(BitMatrix matrix) throws Exception {
        assertNotNull(matrix);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new MatrixLuminanceSource(matrix)));
        Result result = new QRCodeReader().decode(bitmap);
        return result.getText();
    }

    /** 把模块矩阵当成灰度图喂给解码器：置位的模块是黑(0)，其余是白(255)。 */
    private static final class MatrixLuminanceSource extends LuminanceSource {
        private final BitMatrix matrix;

        MatrixLuminanceSource(BitMatrix matrix) {
            super(matrix.getWidth(), matrix.getHeight());
            this.matrix = matrix;
        }

        @Override
        public byte[] getRow(int y, byte[] row) {
            int width = getWidth();
            if (row == null || row.length < width) {
                row = new byte[width];
            }
            for (int x = 0; x < width; x++) {
                row[x] = (byte) (matrix.get(x, y) ? 0 : 0xFF);
            }
            return row;
        }

        @Override
        public byte[] getMatrix() {
            int width = getWidth();
            int height = getHeight();
            byte[] pixels = new byte[width * height];
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    pixels[offset + x] = (byte) (matrix.get(x, y) ? 0 : 0xFF);
                }
            }
            return pixels;
        }
    }
}
