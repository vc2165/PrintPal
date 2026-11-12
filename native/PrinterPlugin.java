package com.vinay.printpal;

import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintJob;
import android.print.PrintJobInfo;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

/**
 * Native side of PrintPal. Everything is printed through Android's own print system
 * (PrintManager), so any printer that Android can see (via Mopria or the printer brand's
 * print service) works, and the system dialog handles printer choice, copies, paper,
 * colour, page range and double-sided.
 */
@CapacitorPlugin(name = "Printer")
public class PrinterPlugin extends Plugin {

    private final Handler main = new Handler(Looper.getMainLooper());
    // Keeps off-screen WebViews alive until the print system has finished reading them
    private final List<WebView> printWebViews = new ArrayList<>();
    private JSObject pendingShare = null;

    // ---------- Shared files / links from other apps ----------

    @Override
    public void load() {
        Intent intent = getActivity().getIntent();
        if (intent != null) handleShareIntent(intent);
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        handleShareIntent(intent);
    }

    private void handleShareIntent(Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action) && !Intent.ACTION_VIEW.equals(action)) return;

        final List<Uri> uris = new ArrayList<>();
        String text = null;
        try {
            if (Intent.ACTION_SEND.equals(action)) {
                Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (u != null) uris.add(u);
                CharSequence t = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (t != null) text = t.toString();
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (list != null) uris.addAll(list);
            } else if (intent.getData() != null) {
                uris.add(intent.getData());
            }
        } catch (Exception ignored) {}
        final String sharedText = text;
        // Don't import the same share again if the activity is recreated
        getActivity().setIntent(new Intent(getContext(), getActivity().getClass()));
        if (uris.isEmpty() && sharedText == null) return;

        new Thread(() -> {
            JSObject result = new JSObject();
            JSArray items = new JSArray();
            File dir = new File(getContext().getCacheDir(), "shared");
            dir.mkdirs();
            for (Uri uri : uris) {
                try {
                    JSObject item = copyUri(uri, dir);
                    if (item != null) items.put(item);
                } catch (Exception e) {
                    JSObject bad = new JSObject();
                    bad.put("error", "Couldn't open a shared file: " + e.getMessage());
                    items.put(bad);
                }
            }
            result.put("items", items);
            if (sharedText != null) result.put("text", sharedText);
            synchronized (PrinterPlugin.this) {
                pendingShare = result;
            }
            notifyListeners("shared", new JSObject());
        }).start();
    }

    private JSObject copyUri(Uri uri, File dir) throws Exception {
        ContentResolver cr = getContext().getContentResolver();
        String name = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = cr.query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
                if (c != null && c.moveToFirst()) name = c.getString(0);
            } catch (Exception ignored) {}
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "file";
        name = safeName(name);
        String mime = cr.getType(uri);
        File out = uniqueFile(dir, name);
        try (InputStream in = cr.openInputStream(uri); OutputStream os = new FileOutputStream(out)) {
            if (in == null) return null;
            copy(in, os);
        }
        JSObject item = new JSObject();
        item.put("name", name);
        item.put("mime", mime == null ? "" : mime);
        item.put("path", out.getAbsolutePath());
        item.put("size", out.length());
        return item;
    }

    @PluginMethod
    public synchronized void getShared(PluginCall call) {
        JSObject r = pendingShare == null ? new JSObject() : pendingShare;
        pendingShare = null;
        call.resolve(r);
    }

    // ---------- Temp files (files picked inside the app are streamed here in chunks) ----------

    @PluginMethod
    public void writeTemp(PluginCall call) {
        String data = call.getString("data", "");
        String name = safeName(call.getString("name", "file"));
        boolean append = call.getBoolean("append", false);
        String path = call.getString("path");
        try {
            File f;
            if (append && path != null) {
                f = new File(path);
                if (!isInCache(f)) {
                    call.reject("Bad path");
                    return;
                }
            } else {
                File dir = new File(getContext().getCacheDir(), "work");
                dir.mkdirs();
                f = uniqueFile(dir, name);
            }
            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            try (OutputStream os = new FileOutputStream(f, append)) {
                os.write(bytes);
            }
            JSObject r = new JSObject();
            r.put("path", f.getAbsolutePath());
            call.resolve(r);
        } catch (Exception e) {
            call.reject("Couldn't save file: " + e.getMessage());
        }
    }

    @PluginMethod
    public void clearTemp(PluginCall call) {
        deleteChildren(new File(getContext().getCacheDir(), "work"));
        deleteChildren(new File(getContext().getCacheDir(), "shared"));
        call.resolve();
    }

    // ---------- Printing ----------

    @PluginMethod
    public void printPdf(PluginCall call) {
        final File file = new File(call.getString("path", ""));
        final String name = jobName(call.getString("name", "Document"));
        if (!file.exists() || !isInCache(file)) {
            call.reject("File not found");
            return;
        }
        int pages = PrintDocumentInfo.PAGE_COUNT_UNKNOWN;
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer r = new PdfRenderer(pfd)) {
            pages = r.getPageCount();
        } catch (SecurityException e) {
            call.reject("This PDF is password-protected. Open it in a PDF app, remove the password or use that app's print option.");
            return;
        } catch (Exception ignored) {}
        final int pageCount = pages;

        PrintDocumentAdapter adapter = new PrintDocumentAdapter() {
            @Override
            public void onLayout(PrintAttributes oldA, PrintAttributes newA, CancellationSignal cancel, LayoutResultCallback cb, Bundle extras) {
                if (cancel.isCanceled()) {
                    cb.onLayoutCancelled();
                    return;
                }
                PrintDocumentInfo info = new PrintDocumentInfo.Builder(name + ".pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(pageCount)
                    .build();
                cb.onLayoutFinished(info, oldA == null || !newA.equals(oldA));
            }

            @Override
            public void onWrite(PageRange[] ranges, ParcelFileDescriptor dest, CancellationSignal cancel, WriteResultCallback cb) {
                try (InputStream in = new FileInputStream(file); OutputStream out = new FileOutputStream(dest.getFileDescriptor())) {
                    copy(in, out);
                    cb.onWriteFinished(new PageRange[] { PageRange.ALL_PAGES });
                } catch (Exception e) {
                    cb.onWriteFailed(e.getMessage());
                }
            }
        };
        startPrint(call, name, adapter, attrs(call));
    }

    @PluginMethod
    public void printHtml(PluginCall call) {
        final String html = call.getString("html", "");
        final String name = jobName(call.getString("name", "Document"));
        final PrintAttributes attrs = attrs(call);
        main.post(() -> {
            WebView wv = makePrintWebView(call, name, attrs, false);
            wv.loadDataWithBaseURL("https://printpal.local/", html, "text/html", "utf-8", null);
        });
    }

    @PluginMethod
    public void printUrl(PluginCall call) {
        final String url = call.getString("url", "");
        final String name = jobName(call.getString("name", url));
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            call.reject("Enter a web address starting with http:// or https://");
            return;
        }
        final PrintAttributes attrs = attrs(call);
        main.post(() -> {
            WebView wv = makePrintWebView(call, name, attrs, true);
            wv.loadUrl(url);
        });
    }

    private WebView makePrintWebView(PluginCall call, String name, PrintAttributes attrs, boolean web) {
        WebView wv = new WebView(getActivity());
        wv.getSettings().setJavaScriptEnabled(web);
        wv.getSettings().setDomStorageEnabled(web);
        wv.getSettings().setLoadWithOverviewMode(true);
        wv.getSettings().setUseWideViewPort(web);
        printWebViews.add(wv);
        final boolean[] done = { false };
        final Runnable[] fallback = { null };
        final Runnable go = () -> {
            if (done[0]) return;
            done[0] = true;
            if (fallback[0] != null) main.removeCallbacks(fallback[0]);
            PrintDocumentAdapter inner = wv.createPrintDocumentAdapter(name);
            startPrint(call, name, new ReleasingAdapter(inner, wv), attrs);
        };
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Let late images/scripts settle a moment before printing
                main.postDelayed(go, web ? 1500 : 300);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });
        if (web) {
            // Some pages never finish loading (ads, trackers): print what we have after 25 s
            fallback[0] = go;
            main.postDelayed(go, 25000);
        }
        return wv;
    }

    /** Wraps a WebView adapter so the WebView is released when printing finishes. */
    private class ReleasingAdapter extends PrintDocumentAdapter {
        private final PrintDocumentAdapter inner;
        private final WebView wv;

        ReleasingAdapter(PrintDocumentAdapter inner, WebView wv) {
            this.inner = inner;
            this.wv = wv;
        }

        @Override
        public void onStart() {
            inner.onStart();
        }

        @Override
        public void onLayout(PrintAttributes o, PrintAttributes n, CancellationSignal c, LayoutResultCallback cb, Bundle e) {
            inner.onLayout(o, n, c, cb, e);
        }

        @Override
        public void onWrite(PageRange[] p, ParcelFileDescriptor d, CancellationSignal c, WriteResultCallback cb) {
            inner.onWrite(p, d, c, cb);
        }

        @Override
        public void onFinish() {
            inner.onFinish();
            main.post(() -> {
                printWebViews.remove(wv);
                wv.destroy();
            });
        }
    }

    /** Prints page images (already laid out by the app) – one image per page. */
    @PluginMethod
    public void printImages(PluginCall call) {
        final List<File> files = imageFiles(call);
        if (files == null) return;
        final String name = jobName(call.getString("name", "Photos"));
        final boolean fill = "fill".equals(call.getString("fit", "fit"));
        final boolean photo = call.getBoolean("photo", true);

        PrintDocumentAdapter adapter = new PrintDocumentAdapter() {
            PrintAttributes current;

            @Override
            public void onLayout(PrintAttributes oldA, PrintAttributes newA, CancellationSignal cancel, LayoutResultCallback cb, Bundle extras) {
                if (cancel.isCanceled()) {
                    cb.onLayoutCancelled();
                    return;
                }
                current = newA;
                PrintDocumentInfo info = new PrintDocumentInfo.Builder(name + ".pdf")
                    .setContentType(photo ? PrintDocumentInfo.CONTENT_TYPE_PHOTO : PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(files.size())
                    .build();
                cb.onLayoutFinished(info, oldA == null || !newA.equals(oldA));
            }

            @Override
            public void onWrite(PageRange[] ranges, ParcelFileDescriptor dest, CancellationSignal cancel, WriteResultCallback cb) {
                PdfDocument doc = new PdfDocument();
                try {
                    PrintAttributes.MediaSize ms = current.getMediaSize();
                    int w = (int) Math.round(ms.getWidthMils() * 72 / 1000.0);
                    int h = (int) Math.round(ms.getHeightMils() * 72 / 1000.0);
                    List<PageRange> written = new ArrayList<>();
                    for (int i = 0; i < files.size(); i++) {
                        if (cancel.isCanceled()) {
                            cb.onWriteCancelled();
                            return;
                        }
                        if (!inRanges(ranges, i)) continue;
                        PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(w, h, i).create());
                        drawImage(page.getCanvas(), files.get(i), w, h, fill);
                        doc.finishPage(page);
                        written.add(new PageRange(i, i));
                    }
                    try (OutputStream out = new FileOutputStream(dest.getFileDescriptor())) {
                        doc.writeTo(out);
                    }
                    cb.onWriteFinished(written.toArray(new PageRange[0]));
                } catch (Exception e) {
                    cb.onWriteFailed(e.getMessage());
                } finally {
                    doc.close();
                }
            }
        };
        startPrint(call, name, adapter, attrs(call));
    }

    /** Builds a PDF from page images (used by the scanner). Returns its path. */
    @PluginMethod
    public void makePdf(PluginCall call) {
        final List<File> files = imageFiles(call);
        if (files == null) return;
        final String name = safeName(call.getString("name", "Scan"));
        final String paper = call.getString("paper", "A4");
        new Thread(() -> {
            PdfDocument doc = new PdfDocument();
            try {
                File dir = new File(getContext().getCacheDir(), "work");
                dir.mkdirs();
                File out = new File(dir, name.endsWith(".pdf") ? name : name + ".pdf");
                for (int i = 0; i < files.size(); i++) {
                    int w, h;
                    if ("Letter".equals(paper)) {
                        w = 612;
                        h = 792;
                    } else if ("Original".equals(paper)) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(files.get(i).getAbsolutePath(), o);
                        // Treat the scan as 200 dpi
                        w = Math.max(72, Math.round(o.outWidth * 72f / 200f));
                        h = Math.max(72, Math.round(o.outHeight * 72f / 200f));
                    } else {
                        w = 595;
                        h = 842;
                    }
                    PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(w, h, i + 1).create());
                    drawImage(page.getCanvas(), files.get(i), w, h, false);
                    doc.finishPage(page);
                }
                try (OutputStream os = new FileOutputStream(out)) {
                    doc.writeTo(os);
                }
                JSObject r = new JSObject();
                r.put("path", out.getAbsolutePath());
                r.put("size", out.length());
                call.resolve(r);
            } catch (Exception e) {
                call.reject("Couldn't make the PDF: " + e.getMessage());
            } finally {
                doc.close();
            }
        }).start();
    }

    // ---------- Save / share ----------

    @PluginMethod
    public void saveToDownloads(PluginCall call) {
        File f = new File(call.getString("path", ""));
        String name = safeName(call.getString("name", f.getName()));
        String mime = call.getString("mime", "application/pdf");
        if (!f.exists() || !isInCache(f)) {
            call.reject("File not found");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PrintPal");
                Uri uri = getContext().getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new Exception("No storage");
                try (InputStream in = new FileInputStream(f); OutputStream os = getContext().getContentResolver().openOutputStream(uri)) {
                    copy(in, os);
                }
                JSObject r = new JSObject();
                r.put("where", "Downloads/PrintPal/" + name);
                call.resolve(r);
            } else {
                File dir = getContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                if (dir == null) throw new Exception("No storage");
                File out = uniqueFile(dir, name);
                try (InputStream in = new FileInputStream(f); OutputStream os = new FileOutputStream(out)) {
                    copy(in, os);
                }
                JSObject r = new JSObject();
                r.put("where", out.getAbsolutePath());
                call.resolve(r);
            }
        } catch (Exception e) {
            call.reject("Couldn't save: " + e.getMessage());
        }
    }

    @PluginMethod
    public void shareFile(PluginCall call) {
        File f = new File(call.getString("path", ""));
        String mime = call.getString("mime", "application/pdf");
        if (!f.exists() || !isInCache(f)) {
            call.reject("File not found");
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mime);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, "Share");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getActivity().startActivity(chooser);
            call.resolve();
        } catch (Exception e) {
            call.reject("Couldn't share: " + e.getMessage());
        }
    }

    // ---------- Print jobs & setup ----------

    @PluginMethod
    public void getJobs(PluginCall call) {
        main.post(() -> {
            JSArray arr = new JSArray();
            try {
                PrintManager pm = (PrintManager) getActivity().getSystemService(Context.PRINT_SERVICE);
                for (PrintJob job : pm.getPrintJobs()) {
                    PrintJobInfo info = job.getInfo();
                    JSObject j = new JSObject();
                    j.put("id", job.getId().toString());
                    j.put("label", info.getLabel());
                    j.put("state", stateName(info.getState()));
                    j.put("created", info.getCreationTime());
                    j.put("copies", info.getCopies());
                    arr.put(j);
                }
            } catch (Exception ignored) {}
            JSObject r = new JSObject();
            r.put("jobs", arr);
            call.resolve(r);
        });
    }

    @PluginMethod
    public void jobAction(PluginCall call) {
        final String id = call.getString("id", "");
        final String action = call.getString("action", "");
        main.post(() -> {
            PrintManager pm = (PrintManager) getActivity().getSystemService(Context.PRINT_SERVICE);
            for (PrintJob job : pm.getPrintJobs()) {
                if (job.getId().toString().equals(id)) {
                    if ("cancel".equals(action)) job.cancel();
                    else if ("retry".equals(action) && job.isFailed()) job.restart();
                    call.resolve();
                    return;
                }
            }
            call.reject("That print job is no longer in the queue");
        });
    }

    @PluginMethod
    public void getServices(PluginCall call) {
        JSArray arr = new JSArray();
        try {
            PackageManager pm = getContext().getPackageManager();
            List<ResolveInfo> list = pm.queryIntentServices(new Intent("android.printservice.PrintService"), PackageManager.GET_META_DATA);
            for (ResolveInfo ri : list) {
                JSObject s = new JSObject();
                s.put("name", String.valueOf(ri.loadLabel(pm)));
                s.put("package", ri.serviceInfo.packageName);
                arr.put(s);
            }
        } catch (Exception ignored) {}
        JSObject r = new JSObject();
        r.put("services", arr);
        call.resolve(r);
    }

    @PluginMethod
    public void openPrintSettings(PluginCall call) {
        try {
            getActivity().startActivity(new Intent(Settings.ACTION_PRINT_SETTINGS));
        } catch (ActivityNotFoundException e) {
            getActivity().startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
        call.resolve();
    }

    @PluginMethod
    public void openStore(PluginCall call) {
        String pkg = call.getString("package", "org.mopria.printplugin");
        try {
            getActivity().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
        } catch (ActivityNotFoundException e) {
            getActivity().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
        }
        call.resolve();
    }

    // ---------- Helpers ----------

    private void startPrint(PluginCall call, String name, PrintDocumentAdapter adapter, PrintAttributes attrs) {
        main.post(() -> {
            try {
                PrintManager pm = (PrintManager) getActivity().getSystemService(Context.PRINT_SERVICE);
                PrintJob job = pm.print(name, adapter, attrs);
                JSObject r = new JSObject();
                r.put("id", job.getId() == null ? "" : job.getId().toString());
                call.resolve(r);
            } catch (Exception e) {
                call.reject("Couldn't open the print screen: " + e.getMessage());
            }
        });
    }

    private PrintAttributes attrs(PluginCall call) {
        PrintAttributes.Builder b = new PrintAttributes.Builder();
        String paper = call.getString("paper", "");
        boolean landscape = call.getBoolean("landscape", false);
        PrintAttributes.MediaSize ms = null;
        if ("A4".equals(paper)) ms = PrintAttributes.MediaSize.ISO_A4;
        else if ("Letter".equals(paper)) ms = PrintAttributes.MediaSize.NA_LETTER;
        else if ("A5".equals(paper)) ms = PrintAttributes.MediaSize.ISO_A5;
        else if ("4x6".equals(paper)) ms = PrintAttributes.MediaSize.NA_INDEX_4X6;
        else if ("Legal".equals(paper)) ms = PrintAttributes.MediaSize.NA_LEGAL;
        if (ms != null) b.setMediaSize(landscape ? ms.asLandscape() : ms.asPortrait());
        String color = call.getString("color", "");
        if ("bw".equals(color)) b.setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME);
        else if ("color".equals(color)) b.setColorMode(PrintAttributes.COLOR_MODE_COLOR);
        return b.build();
    }

    private List<File> imageFiles(PluginCall call) {
        JSArray paths = call.getArray("paths");
        List<File> files = new ArrayList<>();
        try {
            JSONArray a = paths == null ? new JSONArray() : paths;
            for (int i = 0; i < a.length(); i++) {
                File f = new File(a.getString(i));
                if (!f.exists() || !isInCache(f)) throw new Exception("missing page " + (i + 1));
                files.add(f);
            }
        } catch (Exception e) {
            call.reject("Couldn't read the pages: " + e.getMessage());
            return null;
        }
        if (files.isEmpty()) {
            call.reject("Nothing to print");
            return null;
        }
        return files;
    }

    /** Draws an image file onto a page of w×h points, fitted (or filled) and centred. */
    private void drawImage(Canvas canvas, File file, int w, int h, boolean fill) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), o);
        // Aim for ~300 dpi on the page, but never decode more than we need
        int maxPx = Math.max(w, h) * 300 / 72;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), d);
        if (bmp == null) return;
        canvas.drawColor(Color.WHITE);
        float bw = bmp.getWidth(), bh = bmp.getHeight();
        float scale = fill ? Math.max(w / bw, h / bh) : Math.min(w / bw, h / bh);
        float dw = bw * scale, dh = bh * scale;
        float left = (w - dw) / 2f, top = (h - dh) / 2f;
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.save();
        canvas.clipRect(0, 0, w, h);
        canvas.drawBitmap(bmp, null, new RectF(left, top, left + dw, top + dh), p);
        canvas.restore();
        bmp.recycle();
    }

    private static boolean inRanges(PageRange[] ranges, int page) {
        for (PageRange r : ranges) {
            if (r.equals(PageRange.ALL_PAGES)) return true;
            if (page >= r.getStart() && page <= r.getEnd()) return true;
        }
        return false;
    }

    private static String stateName(int s) {
        switch (s) {
            case PrintJobInfo.STATE_CREATED:
                return "preparing";
            case PrintJobInfo.STATE_QUEUED:
                return "queued";
            case PrintJobInfo.STATE_STARTED:
                return "printing";
            case PrintJobInfo.STATE_BLOCKED:
                return "blocked";
            case PrintJobInfo.STATE_COMPLETED:
                return "done";
            case PrintJobInfo.STATE_FAILED:
                return "failed";
            case PrintJobInfo.STATE_CANCELED:
                return "cancelled";
            default:
                return "unknown";
        }
    }

    private boolean isInCache(File f) {
        try {
            return f.getCanonicalPath().startsWith(getContext().getCacheDir().getCanonicalPath() + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    private static String jobName(String s) {
        if (s == null || s.trim().isEmpty()) return "PrintPal";
        s = s.trim();
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static String safeName(String s) {
        s = s.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", "_").trim();
        if (s.isEmpty()) s = "file";
        return s.length() > 120 ? s.substring(s.length() - 120) : s;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static void deleteChildren(File dir) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) deleteChildren(f);
            f.delete();
        }
    }
}
