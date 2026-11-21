package eu.siacs.conversations.utils;

import android.graphics.Color;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.hsluv.HsluvColorConverter;

public class XEP0392Helper {

    private static double angle(String nickname) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(nickname.getBytes(StandardCharsets.UTF_8));
            int angle = ((int) (digest[0]) & 0xff) + ((int) (digest[1]) & 0xff) * 256;
            return angle / 65536.;
        } catch (Exception e) {
            return 0.0;
        }
    }

    public static int rgbFromNick(String name) {
        final HsluvColorConverter converter = new HsluvColorConverter();
        converter.hsluv_h = angle(name) * 360;
        converter.hsluv_s = 85;
        converter.hsluv_l = 58;
        converter.hsluvToRgb();
        return Color.rgb(
                (int) Math.round(converter.rgb_r * 255),
                (int) Math.round(converter.rgb_g * 255),
                (int) Math.round(converter.rgb_b * 255));
    }
}
