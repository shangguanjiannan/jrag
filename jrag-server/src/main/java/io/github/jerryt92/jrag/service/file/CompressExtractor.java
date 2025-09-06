package io.github.jerryt92.jrag.service.file;

import com.google.common.io.Files;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.utils.IOUtils;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 压缩文件提取器
 */
@Slf4j
@Service
public class CompressExtractor {


    /**
     * 从压缩文件中提取文件
     *
     * @param multipartFile 上传的压缩文件
     * @return Map<String, MultipartFile> 其中key为文件在压缩包内的路径，value为对应的文件
     * @throws IOException      如果发生I/O错误
     * @throws RuntimeException 如果文件类型不受支持或解压失败
     */
    public Map<String, MultipartFile> extract(MultipartFile multipartFile) throws IOException {
        if (multipartFile == null || multipartFile.isEmpty()) {
            return new HashMap<>();
        }
        String filename = StringUtils.cleanPath(Objects.requireNonNull(multipartFile.getOriginalFilename()));
        String lowerFilename = filename.toLowerCase();
        if (!lowerFilename.endsWith(".zip") && !lowerFilename.endsWith(".tar") && !lowerFilename.endsWith(".tar.gz")) {
            String errorMsg = "Unsupported file type: " + Files.getFileExtension(lowerFilename);
            log.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }
        // 对于 zip, tar, tar.gz, 等，使用 Commons Compress
        return extractWithCommonsCompress(multipartFile);
    }

    /**
     * 使用 Apache Commons Compress 提取文件。
     * 支持 zip, tar, tgz, tar.gz, tar.bz2 等。
     */
    private Map<String, MultipartFile> extractWithCommonsCompress(MultipartFile multipartFile) throws IOException {
        Map<String, MultipartFile> extractedFiles = new HashMap<>();
        // 使用 try-with-resources 确保流被正确关闭
        // BufferedInputStream 用于提高性能，并支持 mark/reset，这对于某些格式检测是必需的
        try (InputStream is = multipartFile.getInputStream();
             BufferedInputStream bis = new BufferedInputStream(is);
             ArchiveInputStream<?> archiveInputStream = new ArchiveStreamFactory().createArchiveInputStream(bis)) {
            ArchiveEntry entry;
            while ((entry = archiveInputStream.getNextEntry()) != null) {
                if (!archiveInputStream.canReadEntryData(entry) || entry.isDirectory()) {
                    continue;
                }
                // 将entry的内容读入字节数组
                // 注意：这会将整个文件加载到内存，对于非常大的文件可能导致OOM
                byte[] content = IOUtils.toByteArray(archiveInputStream);
                MultipartFile extracted = new InMemoryMultipartFile(entry.getName(), entry.getName(), null, content);
                extractedFiles.put(entry.getName(), extracted);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract file with Commons Compress: " + e.getMessage(), e);
        }
        return extractedFiles;
    }


    /**
     * MultipartFile 的一个内存实现，用于存储从压缩文件中提取的数据。
     */
    private static class InMemoryMultipartFile implements MultipartFile {

        private final String name;
        private final String originalFilename;
        private final String contentType;
        private final byte[] content;

        public InMemoryMultipartFile(String name, String originalFilename, String contentType, byte[] content) {
            this.name = name;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.content = content;
        }

        @Override
        @NonNull
        public String getName() {
            return this.name;
        }

        @Override
        public String getOriginalFilename() {
            return this.originalFilename;
        }

        @Override
        public String getContentType() {
            return this.contentType;
        }

        @Override
        public boolean isEmpty() {
            return this.content.length == 0;
        }

        @Override
        public long getSize() {
            return this.content.length;
        }

        @Override
        @NonNull
        public byte[] getBytes() {
            return this.content;
        }

        @Override
        @NonNull
        public InputStream getInputStream() {
            return new ByteArrayInputStream(this.content);
        }

        @Override
        public void transferTo(@NonNull File dest) throws IOException, IllegalStateException {
            try (FileOutputStream fos = new FileOutputStream(dest)) {
                fos.write(this.content);
            }
        }
    }
}