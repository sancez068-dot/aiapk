package org.gradle.wrapper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Minimal wrapper bootstrap: downloads the Gradle distribution declared in gradle-wrapper.properties. */
public final class GradleWrapperMain {
    public static void main(String[] args) throws Exception {
        File project = findProjectRoot(new File(System.getProperty("user.dir")).getCanonicalFile());
        File propsFile = new File(project, "gradle/wrapper/gradle-wrapper.properties");
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(propsFile)) { props.load(in); }
        String distributionUrl = props.getProperty("distributionUrl");
        if (distributionUrl == null || !distributionUrl.startsWith("https://")) {
            throw new IllegalStateException("gradle-wrapper.properties must contain an HTTPS distributionUrl");
        }
        String archiveName = distributionUrl.substring(distributionUrl.lastIndexOf('/') + 1);
        if (!archiveName.endsWith(".zip")) throw new IllegalStateException("Expected a Gradle ZIP distribution");
        String folderName = archiveName.substring(0, archiveName.length() - 4);
        if (folderName.endsWith("-bin") || folderName.endsWith("-all")) folderName = folderName.substring(0, folderName.lastIndexOf('-'));
        String gradleUserHome = System.getenv("GRADLE_USER_HOME");
        if (gradleUserHome == null || gradleUserHome.trim().isEmpty()) {
            gradleUserHome = new File(System.getProperty("user.home"), ".gradle").getAbsolutePath();
        }
        File cache = new File(new File(new File(gradleUserHome, "wrapper/dists/orbit-bootstrap"), folderName), "");
        File gradleHome = new File(cache, folderName);
        File executable = new File(gradleHome, isWindows() ? "bin/gradle.bat" : "bin/gradle");
        if (!executable.isFile()) {
            if (!cache.exists() && !cache.mkdirs()) throw new IllegalStateException("Cannot create Gradle cache: " + cache);
            File zip = new File(cache, archiveName);
            if (!zip.isFile() || zip.length() < 1024 * 1024) download(distributionUrl, zip);
            verifySha256(zip, props.getProperty("distributionSha256Sum"));
            extract(zip, cache);
            executable = new File(gradleHome, isWindows() ? "bin/gradle.bat" : "bin/gradle");
            if (!executable.isFile()) throw new IllegalStateException("Gradle distribution extracted, but executable was not found: " + executable);
            if (!isWindows()) executable.setExecutable(true, false);
        }
        String[] command = new String[args.length + (isWindows() ? 3 : 1)];
        int offset = 0;
        if (isWindows()) {
            command[0] = System.getenv().getOrDefault("ComSpec", "cmd.exe");
            command[1] = "/c";
            command[2] = executable.getAbsolutePath();
            offset = 3;
        } else {
            command[0] = executable.getAbsolutePath();
            offset = 1;
        }
        System.arraycopy(args, 0, command, offset, args.length);
        Process process = new ProcessBuilder(command).directory(project).inheritIO().start();
        System.exit(process.waitFor());
    }

    private static File findProjectRoot(File dir) throws Exception {
        File current = dir;
        while (current != null) {
            if (new File(current, "gradle/wrapper/gradle-wrapper.properties").isFile()) return current;
            current = current.getParentFile();
        }
        throw new IllegalStateException("Run Gradle from this project or one of its subdirectories.");
    }

    private static void download(String url, File target) throws Exception {
        File temporary = new File(target.getParentFile(), target.getName() + ".part");
        if (temporary.exists()) temporary.delete();
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "ORBIT-Gradle-Bootstrap/1.0");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("Gradle download failed: HTTP " + status);
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(temporary))) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                long total = 0;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    total += read;
                    if (total > 2L * 1024L * 1024L * 1024L) throw new IllegalStateException("Gradle distribution exceeds 2 GiB");
                }
            }
            if (!temporary.renameTo(target)) {
                try (InputStream in = new FileInputStream(temporary); FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[65536]; int n; while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                }
                temporary.delete();
            }
        } finally {
            connection.disconnect();
            temporary.delete();
        }
    }

    private static void verifySha256(File file, String expected) throws Exception {
        if (expected == null || expected.trim().isEmpty()) {
            throw new IllegalStateException("distributionSha256Sum is required; refusing to execute an unverified Gradle distribution.");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) >= 0) digest.update(buffer, 0, n);
        }
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        String normalizedExpected = expected.trim().toLowerCase(Locale.ROOT);
        if (!actual.toString().equals(normalizedExpected)) {
            file.delete();
            throw new IllegalStateException("Gradle distribution SHA-256 mismatch. Expected " + normalizedExpected + ", got " + actual);
        }
    }

    private static void extract(File zipFile, File destination) throws Exception {
        String base = destination.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = zip.getNextEntry()) != null) {
                File output = new File(destination, entry.getName()).getCanonicalFile();
                if (!output.getPath().startsWith(base)) throw new IllegalStateException("Unsafe entry in Gradle distribution ZIP");
                if (entry.isDirectory()) {
                    if (!output.exists() && !output.mkdirs()) throw new IllegalStateException("Cannot create directory: " + output);
                } else {
                    File parent = output.getParentFile();
                    if (!parent.exists() && !parent.mkdirs()) throw new IllegalStateException("Cannot create directory: " + parent);
                    try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
                        int n; while ((n = zip.read(buffer)) >= 0) out.write(buffer, 0, n);
                    }
                }
                zip.closeEntry();
            }
        }
        zipFile.delete();
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }
}
