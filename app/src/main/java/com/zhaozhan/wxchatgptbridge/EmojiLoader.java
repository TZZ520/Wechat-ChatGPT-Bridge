package com.zhaozhan.wxchatgptbridge;

import android.app.Application;
import java.io.File;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 在微信自己的已下载表情缓存内按散列查找，不读取或请求消息中的外部网址。 */
final class EmojiLoader {
    private static final Pattern HASH = Pattern.compile("(?i)^[0-9a-f]{32}$");
    private static final Pattern XML_HASH = Pattern.compile(
            "(?i)\\b(?:md5|androidmd5)\\s*=\\s*['\"]([0-9a-f]{32})['\"]");
    private EmojiLoader() {}

    static String identity(String path, String xml) {
        if (path != null && HASH.matcher(path).matches()) return path.toLowerCase(Locale.ROOT);
        if (xml == null || xml.length() > 200_000) return "";
        Matcher match = XML_HASH.matcher(xml);
        return match.find() ? match.group(1).toLowerCase(Locale.ROOT) : "";
    }

    static File find(Application app, String identity, ClassLoader loader) {
        if (app == null || identity == null || !HASH.matcher(identity).matches()) return null;
        File externalFiles = app.getExternalFilesDir(null);
        File[] homes = {new File(app.getDataDir(), "MicroMsg"), externalFiles == null ? null
                : new File(externalFiles.getParentFile(), "MicroMsg")};
        for (File home : homes) {
            if (home == null) continue;
            File[] accounts = home.listFiles(File::isDirectory);
            if (accounts == null) continue;
            int visited = 0;
            for (File account : accounts) {
                if (!HASH.matcher(account.getName()).matches() || ++visited > 64) continue;
                File root = new File(account, "emoji");
                File found = candidate(root, root, identity, loader);
                if (found != null) return found;
                File[] groups = root.listFiles(File::isDirectory);
                if (groups == null) continue;
                for (int i = 0; i < groups.length && i < 1024; i++) {
                    found = candidate(root, groups[i], identity, loader);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private static File candidate(File root, File directory, String hash, ClassLoader loader) {
        for (String suffix : new String[]{"", ".gif", ".png", ".webp", ".jpg"}) {
            try {
                File file = new File(directory, hash + suffix).getCanonicalFile();
                String prefix = root.getCanonicalPath() + File.separator;
                if (!file.getPath().startsWith(prefix) || !file.isFile() || file.length() <= 0
                        || file.length() > 12_000_000) continue;
                if (ImageLoader.readCandidate(file.getPath(), loader) != null) return file;
            } catch (Exception ignored) { }
        }
        return null;
    }
}
