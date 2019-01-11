package kr.ac.hansung.sensorvideo;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FileManager {
    private PrintWriter printWriter;
    private String dataFilename = "";

    public PrintWriter openPrintWriter(String filepath) {
        // Android 의 Phone 경로의 /sensor 폴더 선택
        File filePath = new File(filepath);
        if (!filePath.exists() && !filePath.mkdirs()) {
            Log.i(getClass().getName(), "폴더 생성 실패");
            return null;
        }

        // ex) 2018.07.11 오후 03.57.28
        String fileName = new SimpleDateFormat("yyyy.MM.dd HH.mm.ss", Locale.KOREA).format(new Date()) + ".txt";
        dataFilename = fileName;
        File file = new File(filePath, fileName);

        try {
            FileWriter out = new FileWriter(file, true);
            Log.i(getClass().getName(), "측정 데이터 쓰기 시작 : " + file.getAbsolutePath());
            return printWriter = new PrintWriter(out);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public void closePrintWriter() {
        if (printWriter != null) {
            printWriter.close();
            Log.i(getClass().getName(), "측정 데이터 쓰기 중지");
        }
    }

    public String getDataFilename() {
        return dataFilename;
    }
}
