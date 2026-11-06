package com.sahilprojects.bettingApp.export;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A small writer over Apache POI, so the exports read as "a sheet with these columns and these rows" rather than
 * cell plumbing. Money goes in as a number with a rupee format, so the sheet can still be summed and sorted in
 * Excel; times are shown in India time.
 *
 * Excel itself stores numbers as doubles. That is fine for a sheet people read, and the app never reads these files
 * back, so the BigDecimal rule for the ledger is not broken.
 */
final class Sheets implements AutoCloseable {

    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private final Workbook workbook = new XSSFWorkbook();
    private final CellStyle header;
    private final CellStyle money;
    private final CellStyle dateTime;
    private final CellStyle title;

    Sheets() {
        DataFormat format = workbook.createDataFormat();
        Font bold = workbook.createFont();
        bold.setBold(true);

        header = workbook.createCellStyle();
        header.setFont(bold);
        header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setBorderBottom(BorderStyle.THIN);

        money = workbook.createCellStyle();
        money.setDataFormat(format.getFormat("\"₹\"#,##0.00;[Red]-\"₹\"#,##0.00"));

        dateTime = workbook.createCellStyle();
        dateTime.setDataFormat(format.getFormat("dd mmm yyyy hh:mm"));

        Font big = workbook.createFont();
        big.setBold(true);
        big.setFontHeightInPoints((short) 14);
        title = workbook.createCellStyle();
        title.setFont(big);
    }

    /** A sheet with a header row, frozen so it stays visible while scrolling. */
    Table table(String name, String... columns) {
        Sheet sheet = workbook.createSheet(name);
        Row row = sheet.createRow(0);
        for (int i = 0; i < columns.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(columns[i]);
            cell.setCellStyle(header);
        }
        sheet.createFreezePane(0, 1);
        Table table = new Table(sheet, columns.length);
        for (int i = 0; i < columns.length; i++) table.measure(i, columns[i].length());
        return table;
    }

    /** A two-column sheet of labels and values, under a title. */
    Table summary(String name, String heading) {
        Sheet sheet = workbook.createSheet(name);
        Row row = sheet.createRow(0);
        Cell cell = row.createCell(0);
        cell.setCellValue(heading);
        cell.setCellStyle(title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));
        Table table = new Table(sheet, 2);
        table.next = 2;
        return table;
    }

    byte[] toBytes() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            workbook.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    final class Table {
        private final Sheet sheet;
        private final int columns;
        private int next = 1;
        /** Widest value per column, in characters. Measured here rather than with POI autoSizeColumn, which needs
         *  fonts installed on the server and fails on a bare Linux container. */
        private final int[] widths = new int[16];

        private Table(Sheet sheet, int columns) {
            this.sheet = sheet;
            this.columns = columns;
        }

        /** Adds a row. Values may be String, Number, BigDecimal (money), Instant (time), Boolean or null. */
        Table row(Object... values) {
            Row row = sheet.createRow(next++);
            for (int i = 0; i < values.length; i++) {
                Object v = values[i];
                if (v == null) continue;
                measure(i, v instanceof Instant ? 17 : v instanceof BigDecimal ? v.toString().length() + 4 : v.toString().length());
                Cell cell = row.createCell(i);
                if (v instanceof BigDecimal amount) {
                    cell.setCellValue(amount.doubleValue());
                    cell.setCellStyle(money);
                } else if (v instanceof Number n) {
                    cell.setCellValue(n.doubleValue());
                } else if (v instanceof Instant t) {
                    cell.setCellValue(t.atZone(ZONE).toLocalDateTime());
                    cell.setCellStyle(dateTime);
                } else if (v instanceof Boolean b) {
                    cell.setCellValue(b ? "Yes" : "No");
                } else {
                    cell.setCellValue(v.toString());
                }
            }
            return this;
        }

        Table blank() {
            next++;
            return this;
        }

        private void measure(int column, int length) {
            if (column < widths.length) widths[column] = Math.max(widths[column], length);
        }

        /** Sizes the columns to their contents. Called once the rows are in. */
        void fit() {
            for (int i = 0; i < Math.max(columns, 2); i++) {
                sheet.setColumnWidth(i, Math.min(Math.max(widths[i], 8) + 2, 60) * 256);
            }
        }
    }
}
