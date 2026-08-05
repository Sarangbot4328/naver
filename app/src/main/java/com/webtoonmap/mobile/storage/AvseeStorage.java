package com.webtoonmap.mobile.storage;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.util.Locale;
import java.util.UUID;

public final class AvseeStorage {
    private AvseeStorage() { }

    public static File root(Context context) {
        File external = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        File root = external == null ? new File(context.getFilesDir(), "avsee") :
                new File(external, "avsee");
        if (!root.exists() && !root.mkdirs()) {
            root = new File(context.getFilesDir(), "avsee");
            if (!root.exists()) root.mkdirs();
        }
        return root;
    }

    public static File createVideoFolder(Context context, String title) {
        String safe = safeName(title);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        File folder = new File(root(context), safe + "_" + suffix);
        if (!folder.mkdirs()) throw new IllegalStateException("영상 저장 폴더를 만들 수 없습니다.");
        return folder;
    }

    public static void deleteVideoFiles(String filePath) {
        if (filePath == null || filePath.isEmpty()) return;
        File file = new File(filePath);
        File folder = file.getParentFile();
        if (folder == null || !folder.isDirectory()) return;
        deleteFolder(folder);
    }

    public static void cleanupIncomplete(Context context) {
        File[] folders = root(context).listFiles();
        if (folders == null) return;
        for (File folder : folders) {
            if (!folder.isDirectory()) continue;
            File[] children = folder.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (child.getName().endsWith(".part")) {
                    deleteFolder(folder);
                    break;
                }
            }
        }
    }

    public static CleanupResult clearTemporaryData(Context context) {
        int removedFiles = 0;
        int removedFolders = 0;
        long removedBytes = 0L;

        File[] videoFolders = root(context).listFiles();
        if (videoFolders != null) {
            for (File folder : videoFolders) {
                if (!folder.isDirectory() || !containsPartialFile(folder)) continue;
                FileStats stats = measure(folder);
                deleteFolder(folder);
                if (!folder.exists()) {
                    removedFiles += stats.files;
                    removedFolders += stats.folders;
                    removedBytes += stats.bytes;
                }
            }
        }

        String[] cacheNames = {
                "shared", "exports", "imports", "import-stage", "download-zips", "viewer"
        };
        for (String name : cacheNames) {
            File folder = new File(context.getCacheDir(), name);
            if (!folder.exists()) continue;
            FileStats stats = measure(folder);
            deleteFolder(folder);
            if (!folder.exists()) {
                removedFiles += stats.files;
                removedFolders += stats.folders;
                removedBytes += stats.bytes;
            }
        }
        return new CleanupResult(removedFiles, removedFolders, removedBytes);
    }

    private static boolean containsPartialFile(File folder) {
        File[] children = folder.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (child.isDirectory() && containsPartialFile(child)) return true;
            String name = child.getName().toLowerCase(Locale.US);
            if (name.endsWith(".part") || name.contains(".part-") || name.endsWith(".tmp")) {
                return true;
            }
        }
        return false;
    }

    private static FileStats measure(File file) {
        if (file == null || !file.exists()) return new FileStats();
        FileStats stats = new FileStats();
        if (file.isFile()) {
            stats.files = 1;
            stats.bytes = file.length();
            return stats;
        }
        stats.folders = 1;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                FileStats childStats = measure(child);
                stats.files += childStats.files;
                stats.folders += childStats.folders;
                stats.bytes += childStats.bytes;
            }
        }
        return stats;
    }
    public static void deleteFolder(File folder) {
        if (folder == null || !folder.exists()) return;
        File[] children = folder.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) deleteFolder(child);
                else child.delete();
            }
        }
        folder.delete();
    }

    public static String extensionFromUrl(String url) {
        if (url == null) return ".mp4";
        String clean = url.split("[?#]", 2)[0].toLowerCase(Locale.US);
        for (String ext : new String[]{".mp4", ".webm", ".m4v", ".mov"}) {
            if (clean.endsWith(ext)) return ext;
        }
        return ".mp4";
    }

    public static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.KOREA, "%.1f GB", bytes / (1024d * 1024d * 1024d));
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.KOREA, "%.1f MB", bytes / (1024d * 1024d));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.KOREA, "%.1f KB", bytes / 1024d);
        }
        return bytes + " B";
    }

    public static final class CleanupResult {
        public final int removedFiles;
        public final int removedFolders;
        public final long removedBytes;

        CleanupResult(int removedFiles, int removedFolders, long removedBytes) {
            this.removedFiles = removedFiles;
            this.removedFolders = removedFolders;
            this.removedBytes = removedBytes;
        }

        public boolean isEmpty() {
            return removedFiles == 0 && removedBytes == 0L;
        }
    }

    private static final class FileStats {
        int files;
        int folders;
        long bytes;
    }
    private static String safeName(String title) {
        String value = title == null ? "avsee" : title.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ");
        if (value.isEmpty()) value = "avsee";
        return value.substring(0, Math.min(value.length(), 54));
    }
}
