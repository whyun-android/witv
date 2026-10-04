package com.whyun.witv.server;

import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * 把 Web 管理地址渲染成二维码，省去在电视上用遥控器逐字符输入 URL。
 */
public final class QrCodeUtil {

    /**
     * 静区（quiet zone）模块数。低于 2 很多手机扫不出来，而这里是贴在深色背景上的小图，
     * 必须靠白色静区把码区和背景隔开。
     */
    private static final int QUIET_ZONE_MODULES = 2;

    private QrCodeUtil() {
    }

    /**
     * 生成黑白二维码位图。
     *
     * <p>固定黑底白码而不跟随应用配色：扫码依赖足够的明暗对比，用主题色很容易扫不出来。
     *
     * @param content 要编码的内容，通常是 {@code http://<ip>:9979}
     * @param sizePx  目标边长（像素），应当按实际显示尺寸传入，避免缩放后模块边缘发虚
     * @return 位图；内容为空或编码失败时返回 null，调用方应隐藏对应视图
     */
    @Nullable
    public static Bitmap encode(@Nullable String content, int sizePx) {
        BitMatrix matrix = encodeMatrix(content, sizePx);
        if (matrix == null) {
            return null;
        }
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    /**
     * 编码出模块矩阵。与位图转换分开，便于在 JVM 单元测试里直接验证编码结果。
     *
     * @return 矩阵；内容为空或 zxing 编码失败时返回 null
     */
    @Nullable
    @VisibleForTesting
    static BitMatrix encodeMatrix(@Nullable String content, int sizePx) {
        if (content == null || content.trim().isEmpty() || sizePx <= 0) {
            return null;
        }
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, QUIET_ZONE_MODULES);
        // 这是贴在屏幕上供近距离扫描的码，不需要更高纠错等级；等级越高模块越密、越难扫
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        try {
            return new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
        } catch (WriterException | IllegalArgumentException e) {
            return null;
        }
    }
}
