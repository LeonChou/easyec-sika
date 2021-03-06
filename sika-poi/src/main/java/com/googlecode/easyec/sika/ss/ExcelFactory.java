package com.googlecode.easyec.sika.ss;

import com.googlecode.easyec.sika.*;
import com.googlecode.easyec.sika.converters.Object2StringConverter;
import com.googlecode.easyec.sika.event.RowEvent;
import com.googlecode.easyec.sika.event.WorkbookBlankRowListener;
import com.googlecode.easyec.sika.event.WorkbookHandleEvent;
import com.googlecode.easyec.sika.event.WorkbookHandlerChangeListener;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.Workbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.io.*;
import java.util.*;

import static com.googlecode.easyec.sika.DocType.EXCEL03;
import static com.googlecode.easyec.sika.DocType.EXCEL07;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.apache.poi.ss.usermodel.Cell.*;
import static org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted;

/**
 * Excel文档处理工厂类。
 *
 * @author JunJie
 */
public final class ExcelFactory {

    private static final ThreadLocal<ExcelFactory> local = new ThreadLocal<ExcelFactory>();
    private Logger logger = LoggerFactory.getLogger(ExcelFactory.class);

    private ExcelFactory() {}

    public static ExcelFactory getInstance() {
        synchronized (local) {
            ExcelFactory factory = local.get();
            if (factory == null) {
                factory = new ExcelFactory();
                local.set(factory);
            }

            return factory;
        }
    }

    public void read(File file, WorkbookReader workbookReader) throws WorkingException {
        try {
            read(new FileInputStream(file), workbookReader);
        } catch (FileNotFoundException e) {
            throw new WorkingException(e, true);
        }
    }

    public void read(InputStream in, WorkbookReader reader) throws WorkingException {
        Workbook wb;

        try {
            assertEmptyWorkbookHandler(reader);

            wb = WorkbookFactory.create(in);

            doRead(wb, reader);
        } catch (IOException e) {
            throw new WorkingException(e, true);
        } catch (InvalidFormatException e) {
            throw new WorkingException(e, true);
        } finally {
            IOUtils.closeQuietly(in);

            wb = null;
        }
    }

    public <T> void write(InputStream in, OutputStream out, WorkbookWriter<T> writer) throws WorkingException {
        Assert.notNull(out, "OutputStream object is null.");

        try {
            IOUtils.write(write(in, writer), out);
        } catch (IOException e) {
            throw new WorkingException(e, true);
        }
    }

    public <T> byte[] write(InputStream in, WorkbookWriter<T> writer) throws WorkingException {
        Assert.notNull(in, "InputStream object is null.");
        Assert.notNull(writer, "WorkbookWriter object is null.");

        Workbook wb;

        try {
            wb = WorkbookFactory.create(in);

            return doWrite(wb, writer);
        } catch (IOException e) {
            throw new WorkingException(e, true);
        } catch (InvalidFormatException e) {
            throw new WorkingException(e, true);
        } finally {
            IOUtils.closeQuietly(in);

            wb = null;
        }
    }

    private void assertEmptyWorkbookHandler(com.googlecode.easyec.sika.Workbook workbook) throws WorkingException {
        if (!workbook.hasMore()) {
            throw new WorkingException("No Workbook was added.", true);
        }
    }

    private synchronized <T> byte[] doWrite(Workbook wb, WorkbookWriter<T> writer) throws WorkingException {
        // create a creation helper
        CreationHelper helper = wb.getCreationHelper();

        /*int lastSheetNum = writer.size();
        if (lastSheetNum > wb.getNumberOfSheets()) {
            logger.warn("Excel sheets' size are greater than WorkbookCallback's." +
                    " Sheet's size: [" + wb.getNumberOfSheets() + "]," +
                    " WorkbookCallback's size: [" + lastSheetNum + "]." +
                    " So remaining WorkbookCallback will be ignored.");

            lastSheetNum = wb.getNumberOfSheets();
        }*/

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Set<Integer> sheets = new HashSet<Integer>();

        out:
        for (int i = 0; i < writer.size(); i++) {
            WorkbookCallback<T> callback = writer.get(i);
            WorkPage workPage = callback.getWorkPage();

            int sheetIndex = workPage.getSheetIndex();
            logger.debug("Sheet index is: [" + sheetIndex + "].");

            Sheet sheet;

            try {
                sheet = wb.cloneSheet(sheetIndex);
                if (!sheets.contains(sheetIndex)) {
                    sheets.add(sheetIndex);
                }
            } catch (Exception e) {
                logger.debug(e.getMessage(), e);

                throw new WorkingException(e, true);
            }

            List<T> list;

            try {
                if (isNotBlank(workPage.getSheetName())) {
                    wb.setSheetName(wb.getNumberOfSheets() - 1, workPage.getSheetName());
                }

                callback.doInit();

                try {
                    list = callback.doGrab();
                } catch (WorkingException e) {
                    callback.doCatch(e);

                    // If WorkingException.isStop,
                    // break out all of workbook's callback,
                    // else continue handling others callback.
                    if (e.isStop()) break;
                    else continue;
                }

                int j = 0;
                WorkbookHeader header = callback.getHeader();
                if (header != null && header.hasHeader()) {
                    List<WorkData[]> headerList = header.getHeaderList();
                    for (int m = j; m < headerList.size(); m++) {
                        WorkData[] dataList = headerList.get(m);

                        if (ArrayUtils.isNotEmpty(dataList)) {
                            Row row = sheet.createRow(m);

                            for (int n = 0; n < dataList.length; n++) {
                                WorkData workData = dataList[n];

                                Cell cell = row.createCell(n);
                                switch (workData.getWorkDataType()) {
                                    case NUMBER:
                                        cell.setCellValue(((Number) workData.getValue()).doubleValue());
                                        break;
                                    case DATE:
                                        cell.setCellValue(((Date) workData.getValue()));
                                        break;
                                    default:
                                        cell.setCellValue(helper.createRichTextString(workData.getValue(new Object2StringConverter())));
                                }
                            }
                        }
                    }

                    j = header.getHeaderCount();
                }

                if (CollectionUtils.isEmpty(list)) {
                    logger.debug("No records were found.");
                    continue;
                }

                for (int k = 0; k < list.size(); k++, j++) {
                    Row row = sheet.createRow(j);

                    List<WorkData> dataList = null;

                    try {
                        dataList = callback.populate(list.get(k));

                        for (int m = 0; m < dataList.size(); m++) {
                            Cell cell = row.createCell(m);

                            WorkData workData = dataList.get(m);
                            switch (workData.getWorkDataType()) {
                                case NUMBER:
                                    cell.setCellValue(((Number) workData.getValue()).doubleValue());
                                    break;
                                case DATE:
                                    cell.setCellValue(((Date) workData.getValue()));
                                    break;
                                default:
                                    cell.setCellValue(helper.createRichTextString(workData.getValue(new Object2StringConverter())));
                            }
                        }
                    } catch (WorkingException e) {
                        callback.doCatch(e);

                        if (e.isStop()) break out;
                    } finally {
                        if (dataList != null) {
                            dataList.clear();
                            dataList = null;
                        }
                    }
                }

                callback.doFinish();
            } finally {
                callback.doFinally();
            }
        }

        try {
            for (Integer i : sheets) {
                wb.removeSheetAt(i);
            }

            wb.write(out);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);

            throw new WorkingException(e, true);
        }

        return out.toByteArray();
    }

    private synchronized void doRead(Workbook wb, WorkbookReader reader) throws WorkingException {
        // create a formula evaluator
        FormulaEvaluator fe = wb.getCreationHelper().createFormulaEvaluator();

        /*int lastSheetNum = reader.size();
        if (lastSheetNum > wb.getNumberOfSheets()) {
            logger.warn("Excel sheets' size are greater than WorkbookHandler's." +
                    " Sheet's size: [" + wb.getNumberOfSheets() + "]," +
                    " WorkbookHandler's size: [" + lastSheetNum + "]." +
                    " So remaining WorkbookHandlers will be ignored.");

            lastSheetNum = wb.getNumberOfSheets();
        }*/

        int lastSheetNum = wb.getNumberOfSheets();

        for (int i = 0; i < lastSheetNum; i++) {
            WorkbookHandler handler;

            // 为每个sheet取相应索引的Handler。
            // 但是当Handler的可以用数量少于sheet数量，
            // 那么视为读取默认一个索引号的Handler来处理。
            try {
                handler = reader.get(i);
            } catch (Exception e) {
                try {
                    handler = reader.get(0);
                } catch (Exception e1) {
                    logger.error(e.getMessage(), e);

                    throw new WorkingException(e1, true);
                }
            }

            try {
                Sheet sheet = wb.getSheetAt(i);

                WorkPage workPage = new WorkPage(i, sheet.getSheetName());

                if (i > 0) {
                    WorkbookHandlerChangeListener workbookHandlerChangeListener = reader.getWorkbookHandlerChangeListener();
                    if (workbookHandlerChangeListener != null) {
                        boolean b = workbookHandlerChangeListener.accept(new WorkbookHandleEvent(workPage));
                        if (!b) {
                            break;
                        }
                    }
                }

                // start setting workbook properties
                handler.setWorkPage(workPage);
                handler.setDocType((wb instanceof HSSFWorkbook) ? EXCEL03 : EXCEL07);

                handler.doInit();

                if (handler instanceof WorkbookRowHandler) {
                    processSheetPerRow((WorkbookRowHandler) handler, sheet, fe);
                }

                handler.doFinish();
            } finally {
                handler.doFinally();
            }
        }
    }

    private void processSheetPerRow(WorkbookRowHandler handler, Sheet sheet, FormulaEvaluator fe) throws WorkingException {
        int lastRowNum = sheet.getLastRowNum();
        if (lastRowNum < sheet.getPhysicalNumberOfRows()) {
            lastRowNum = sheet.getPhysicalNumberOfRows();
        }

        for (int j = 0; j < lastRowNum; j++) {
            WorkbookHeader header = handler.getHeader();
            if (header.hasHeader() && ((j + 1) <= header.getHeaderCount())) {
                // TODO finish it
                continue;
            }

            Row row = sheet.getRow(j);

            boolean isAllNull = true;
            List<WorkData> list = new ArrayList<WorkData>();
            Map<Integer, List<WorkData>> map = new HashMap<Integer, List<WorkData>>();

            try {
                if (row == null) {
                    logger.debug("Row object is null, so break out.");

                    break;
                }

                int lastCellNum = row.getLastCellNum();
                if (lastCellNum < row.getPhysicalNumberOfCells()) {
                    lastCellNum = row.getPhysicalNumberOfCells();
                }

                for (int k = 0; k < lastCellNum; k++) {
                    Object val = getCellContent(row.getCell(k), fe);
                    if (isAllNull && val != null) {
                        if (val instanceof String) {
                            if (isNotBlank((String) val)) {
                                isAllNull = false;
                            }
                        } else {
                            isAllNull = false;
                        }
                    }

                    list.add(WorkData.createCellWorkData(val, j, k));
                }

                if (isAllNull) {
                    WorkbookBlankRowListener blankRowListeners = handler.getBlankRowListeners();
                    if (blankRowListeners != null) {
                        boolean b = blankRowListeners.accept(new RowEvent(list, j + 1));
                        if (!b) break;
                    }
                }

                map.put(j, list);

                if (!handler.populate(map)) break;
            } catch (WorkingException e) {
                handler.doCatch(e);
                if (e.isStop()) {
                    break;
                }
            } finally {
                if (map != null) {
                    map.clear();
                    map = null;
                }

                list.clear();
                list = null;
            }
        }
    }

    private Object getCellContent(Cell cell, FormulaEvaluator fe) {
        if (cell != null) {
            switch (cell.getCellType()) {
                case CELL_TYPE_BLANK:
                    return "";
                case CELL_TYPE_BOOLEAN:
                    return cell.getBooleanCellValue();
                case CELL_TYPE_ERROR:
                    return cell.getErrorCellValue();
                case CELL_TYPE_FORMULA:
                    return getCellContent(fe.evaluate(cell));
                case CELL_TYPE_NUMERIC:
                    if (isCellDateFormatted(cell)) {
                        return cell.getDateCellValue();
                    }

                    return cell.getNumericCellValue();
                case CELL_TYPE_STRING:
                    return cell.getRichStringCellValue().getString();
            }
        }

        return null;
    }

    private Object getCellContent(CellValue cellVal) {
        if (cellVal != null) {
            switch (cellVal.getCellType()) {
                case CELL_TYPE_BLANK:
                    return "";
                case CELL_TYPE_BOOLEAN:
                    return cellVal.getBooleanValue();
                case CELL_TYPE_ERROR:
                    return cellVal.getErrorValue();
                case CELL_TYPE_NUMERIC:
                    return cellVal.getNumberValue();
                case CELL_TYPE_STRING:
                    return cellVal.getStringValue();
            }
        }

        return null;
    }
}
