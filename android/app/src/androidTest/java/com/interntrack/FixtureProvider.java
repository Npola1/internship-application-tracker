package com.interntrack;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Synthetic Android document-picker inputs and output, installed only by instrumentation tests. */
public class FixtureProvider extends ContentProvider {
    static final byte[] CV="STUDENT CV\nSkills: Java, Git, unit testing\nProject: built an internship application tracker.\n".getBytes(StandardCharsets.UTF_8);
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) throws FileNotFoundException {
        String name=uri.getLastPathSegment();
        if(!java.util.List.of("cv.txt","export.txt","bad.exe","large.pdf").contains(name)) throw new FileNotFoundException("Unknown fixture");
        return new File(getContext().getCacheDir(),"feature-test-"+name);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) {
        String name=uri.getLastPathSegment();
        var cursor=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});
        cursor.addRow(new Object[]{"cv.txt".equals(name)?"Student-CV.txt":name,"large.pdf".equals(name)?10L*1024*1024+1:CV.length});
        return cursor;
    }
    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        File file=file(uri);
        try { if(!"export.txt".equals(uri.getLastPathSegment())) Files.write(file.toPath(),CV); }
        catch(IOException error) { throw new FileNotFoundException("Fixture unavailable"); }
        return ParcelFileDescriptor.open(file,mode.contains("w")?ParcelFileDescriptor.MODE_CREATE|ParcelFileDescriptor.MODE_WRITE_ONLY|ParcelFileDescriptor.MODE_TRUNCATE:ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String selection,String[] args) {
        try { return file(uri).delete()?1:0; } catch(FileNotFoundException error) { return 0; }
    }
}
