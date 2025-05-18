import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class FileTransfer {
    private final String fileName;
    private final long fileSize;
    private final int totalChunks;
    private final Map<Integer, byte[]> chunks = new HashMap<>();
    private final File outputFile;

    public FileTransfer(String fileName, long fileSize, int totalChunks) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.totalChunks = totalChunks;
        this.outputFile = new File("received_" + fileName);
    }

    public void addChunk(int index, byte[] data) {
        chunks.put(index, data);
    }

    public int getProgress() {
        return (int) ((chunks.size() * 100) / totalChunks);
    }

    public boolean isComplete() {
        return chunks.size() == totalChunks;
    }

    public String getFileName() {
        return outputFile.getName();
    }

    public File getFile() {
        return outputFile;
    }

    public void assembleFile() throws IOException {
        if (!isComplete()) {
            throw new IllegalStateException("Cannot assemble incomplete file");
        }

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            for (int i = 0; i < totalChunks; i++) {
                byte[] chunk = chunks.get(i);
                if (chunk != null) {
                    fos.write(chunk);
                }
            }
        }
    }

    public void cleanup() {
        if (outputFile.exists() && !outputFile.delete()) {
            System.err.println("Warning: Could not delete file " + outputFile.getName());
        }
    }
}