package disa.notification.service.utils;

import static java.util.stream.Collectors.groupingBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import disa.notification.service.entity.ViralResultStatistics;
import disa.notification.service.enums.ViralLoadStatus;
import disa.notification.service.service.interfaces.LabResultSummary;
import disa.notification.service.service.interfaces.LabResults;
import disa.notification.service.service.interfaces.PendingHealthFacilitySummary;

public class SyncReport implements XLSColumnConstants {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private MessageSource messageSource;
    
    private DateInterval reportDateInterval;

    private Map<String, String> dictionaries;
  
    public SyncReport(MessageSource messageSource, DateInterval reportDateInterval) {
        this.messageSource = messageSource;
        this.reportDateInterval = reportDateInterval;

        Map<String, String> d = new LinkedHashMap<>(12);
        d.put("Total Recebidos", "Número total de resultados laboratoriais criados no servidor de integração");
        d.put("No. Processados",
                "Número de resultados laboratoriais criados no servidor de integração que foram processados (NID identificado e e-Lab criado no SESP)");
        d.put("Não Processados: No. Resultado inválido",
                "Número de resultados laboratoriais criados no servidor de integração que não foram processados (não tem e-Lab criado no SESP) porque o resultado não tem um valor válido.");
        d.put("Não Processados: No. NID não encontrado",
                "Número de resultados laboratoriais criados no servidor de integração que não foram processados (não tem e-Lab criado no SESP) porque o NID do paciente não foi encontrado no SESP.");
        d.put("Não Processados: No. NID duplicado",
                "Número de resultados laboratoriais criados no servidor de integração que não foram processados (não tem e-Lab criado no SESP) porque o NID do paciente está duplicado no SESP.");
        d.put("Não Processados: No. ID da requisição duplicado",
                "Número de resultados laboratoriais no servidor de integração que não foram processados (não tem e-Lab criado no SESP) porque há um processo com o mesmo código de requisição que já foi processado com sucesso anteriormente.");
        d.put("No. Pendentes",
                "Número de resultados laboratoriais criados no servidor de integração que ainda não foram sincronizados com SESP");
        d.put("Data de Entrada", "Data em que o resultado laboratorial foi criado no servidor de integração");
        d.put("Data de Sincronização",
                "Data em que o entre o servidor de integração sincronizou os resultados laboratoriais com SESP ");
        d.put("Estado",
                "O estado actual do resultado laboratorial no servidor de integração, incluindo: Processado (e-Lab criado em SESP); Não Processado (problemas ao sincronizar com SESP, e-Lab não criado no SESP) ou Pendente (ainda não foi sincronizado com SESP).");
        d.put("Motivo não envio",
                "Se estado for Não Processado, o motivo pode ser NID não encontrado, NID duplicado, ID de requisição duplicado, Sinalizado para revisão, Sem Resultados.");
        d.put("Data da última sincronização ",
                "Data da última tentativa de sincronização entre o servidor de integração e SESP na US");
        dictionaries = Collections.unmodifiableMap(d);
    }

    public ByteArrayResource getViralResultXLS(
            List<LabResultSummary> viralLoaderResultSummary, List<LabResults> viralLoadResults,
            List<LabResults> unsyncronizedViralLoadResults,
            List<PendingHealthFacilitySummary> pendingHealthFacilitySummaries) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream stream = new ByteArrayOutputStream();) {
            composeDictionarySheet(workbook);
            composeReceivedByDistrictSheet(viralLoaderResultSummary, workbook);
            composeReceivedByUSSheet(viralLoaderResultSummary, workbook);
            composeReceivedByNIDSheet(viralLoadResults, workbook);
            composePendingByUSSheet(pendingHealthFacilitySummaries, workbook);
            composePendingByNIDSheet(unsyncronizedViralLoadResults, workbook);
            workbook.write(stream);
            return new ByteArrayResource(stream.toByteArray());
        } catch (IOException ioe) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not generate the file");
        }
    }

    private CellStyle fullBorderThin(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        return style;
    }

    private void composeDictionarySheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Dicionário");

        CellStyle dictionaryHeaderStyle = dictionaryHeaderStyle(workbook);
        Row headerRow = sheet.createRow(FIRST_ROW);
        for (int col = 0; col < DICTIONARY_HEADER.length; col++) {
            Cell cell = headerRow.createCell(col);
            cell.setCellValue(DICTIONARY_HEADER[col]);
            cell.setCellStyle(dictionaryHeaderStyle);
        }

        CellStyle borderThin = fullBorderThin(workbook);
        int counter = headerRow.getRowNum() + 1;
        for (Map.Entry<String, String> d : dictionaries.entrySet()) {
            Row row = sheet.createRow(counter++);
            row.setHeightInPoints(sheet.getDefaultRowHeightInPoints() * 2);
            Cell variable = row.createCell(0);
            variable.setCellValue(d.getKey());
            variable.setCellStyle(borderThin);
            Cell description = row.createCell(1);
            description.setCellValue(d.getValue());
            description.setCellStyle(borderThin);
        }
        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
    }

    public void composeReceivedByDistrictSheet(List<LabResultSummary> viralLoaderResultSummaryList,
            Workbook workbook) {
        String startDateFormatted = reportDateInterval.getStartDateTime().toLocalDate()
                .format(DATE_FORMAT);
        String endDateFormatted = reportDateInterval.getEndDateTime().toLocalDate()
                .format(DATE_FORMAT);
        Sheet sheet4 = workbook.createSheet("Recebidos por Distrito");

        createFirstRow(workbook, sheet4, String.format(STATS_TITLE, startDateFormatted, endDateFormatted), 15);

        // Create headers
        Row headerRow = sheet4.createRow(SECOND_ROW);
        CellStyle headerCellStyle = getHeaderCellStyle(workbook);
        for (ResultsByDistrictSummary r : ResultsByDistrictSummary.values()) {
            Cell cell = headerRow.createCell(r.ordinal());
            cell.setCellValue(r.header());
            cell.setCellStyle(headerCellStyle);
        }

        AtomicInteger counter4 = new AtomicInteger(2);
        Map<String, Map<String, Map<String, ViralResultStatistics>>> provinces = viralLoaderResultSummaryList
                .stream()
                .collect(groupingBy(LabResultSummary::getRequestingProvinceName,
                        groupingBy(LabResultSummary::getRequestingDistrictName,
                                groupingBy(LabResultSummary::getTypeOfResult,
                                        ViralResultStatisticsCollector.toVlResultStatistics()))));

        ViralResultStatistics totals = new ViralResultStatistics();
        provinces.forEach((province, districts) -> {
            districts.forEach((district, typesOfResult) -> {
                typesOfResult.forEach((type, stats) -> {
                    Row row = sheet4.createRow(counter4.getAndIncrement());
                    createStatResultRow(workbook, row, province, district, stats);
                    totals.accumulate(stats);
                });
            });
        });

        Row row = sheet4.createRow(counter4.getAndIncrement());
        createStatLastResultRow(workbook, row, totals);

        sheet4.autoSizeColumn(0);
        sheet4.autoSizeColumn(1);
        sheet4.autoSizeColumn(2);
        sheet4.autoSizeColumn(3);
        sheet4.autoSizeColumn(4);
        sheet4.autoSizeColumn(5);
        sheet4.autoSizeColumn(6);
        sheet4.autoSizeColumn(7);
        sheet4.autoSizeColumn(8);
        sheet4.autoSizeColumn(9);
        sheet4.autoSizeColumn(10);
        sheet4.autoSizeColumn(11);
        sheet4.autoSizeColumn(12);
        sheet4.autoSizeColumn(13);
        sheet4.autoSizeColumn(14);
        sheet4.autoSizeColumn(15);
        sheet4.autoSizeColumn(16);
    }

    private void composePendingByUSSheet(List<PendingHealthFacilitySummary> pendingViralResultSummaries,
            Workbook workbook) {
        Sheet sheet4 = workbook.createSheet("Pendentes por US");
        createFirstRow(workbook, sheet4, RESULTS_PENDING_BY_US_TITLE, 5);
        // Create headers
        Row headerRow = sheet4.createRow(SECOND_ROW);
        CellStyle headerCellStyle = getHeaderCellStyle(workbook);
        for (ResultsPendingByUs r : ResultsPendingByUs.values()) {
            Cell cell = headerRow.createCell(r.ordinal());
            cell.setCellValue(r.header());
            cell.setCellStyle(headerCellStyle);
        }
        AtomicInteger counter4 = new AtomicInteger(2);
        pendingViralResultSummaries.forEach(pendingViralResultSummary -> {
            Row row = sheet4.createRow(counter4.getAndIncrement());
            createPendingViralResultSummaryRow(row, pendingViralResultSummary);
        });
        sheet4.autoSizeColumn(0);
        sheet4.autoSizeColumn(1);
        sheet4.autoSizeColumn(2);
        sheet4.autoSizeColumn(3);
        sheet4.autoSizeColumn(4);
        sheet4.autoSizeColumn(5);
    }

    private void composePendingByNIDSheet(List<LabResults> unsyncronizedViralLoadResults,
            Workbook workbook) {
        Sheet sheet3 = workbook.createSheet("Pendentes por NID");
        createFirstRow(workbook, sheet3, RESULTS_PENDING_BY_NID_TITLE, 6);
        // Create headers
        Row headerRow = sheet3.createRow(SECOND_ROW);
        CellStyle headerCellStyle = getHeaderCellStyle(workbook);
        for (ResultsPendingByNid r : ResultsPendingByNid.values()) {
            Cell cell = headerRow.createCell(r.ordinal());
            cell.setCellValue(r.header());
            cell.setCellStyle(headerCellStyle);
        }
        int rownum = 2;
        for (LabResults viralResult : unsyncronizedViralLoadResults) {
            Row row = sheet3.createRow(rownum++);
            createUnsyncronizedViralResultRow(row, viralResult);
        }
        sheet3.autoSizeColumn(0);
        sheet3.autoSizeColumn(1);
        sheet3.autoSizeColumn(2);
        sheet3.autoSizeColumn(3);
        sheet3.autoSizeColumn(4);
        sheet3.autoSizeColumn(5);
        sheet3.autoSizeColumn(6);
        sheet3.autoSizeColumn(7);
    }

    private void composeReceivedByNIDSheet(List<LabResults> viralLoadResults, Workbook workbook) {
        String startDateFormatted = reportDateInterval.getStartDateTime().format(DATE_FORMAT);
        String endDateFormatted = reportDateInterval.getEndDateTime().format(DATE_FORMAT);
        Sheet sheet2 = workbook.createSheet("Recebidos por NID");
        String title = String.format(VIRAL_RESULT_TITLE, startDateFormatted, endDateFormatted);
        createFirstRow(workbook, sheet2, title, ResultsReceivedByNid.values().length - 1);
        // Create headers
        Row headerRow = sheet2.createRow(SECOND_ROW);
        CellStyle headerCellStyle = getHeaderCellStyle(workbook);
        for (ResultsReceivedByNid r : ResultsReceivedByNid.values()) {
            Cell cell = headerRow.createCell(r.ordinal());
            cell.setCellValue(r.header());
            cell.setCellStyle(headerCellStyle);
        }
        int rowNum = 2;
        for (LabResults viralResult : viralLoadResults) {
            createReceivedByNIDRow(sheet2.createRow(rowNum++), viralResult);
        }
        for (ResultsReceivedByNid r : ResultsReceivedByNid.values()) {
            sheet2.autoSizeColumn(r.ordinal());
        }
    }

    private void composeReceivedByUSSheet(List<LabResultSummary> viralLoaderResultSummary,
            Workbook workbook) {

        // Title
        DateInterval lastWeekInterval = DateTimeUtils.getLastWeekInterVal();
        String startDateFormatted = lastWeekInterval.getStartDateTime().toLocalDate()
                .format(DATE_FORMAT);
        String endDateFormatted = reportDateInterval.getEndDateTime().toLocalDate()
                .format(DATE_FORMAT);
        Sheet sheet = workbook.createSheet("Recebidos por US");
        String title = String.format(VIRAL_RESULT_SUMMARY_TITLE, startDateFormatted, endDateFormatted);
        createFirstRow(workbook, sheet, title, ResultsByHFSummary.values().length - 1);

        // Not Processed group
        CellStyle headerCellStyle = getHeaderCellStyle(workbook);
        Row notProcessedGroupRow = sheet.createRow(SECOND_ROW);
        Cell notProcessedGroupCell = notProcessedGroupRow.createCell(ResultsByHFSummary.TOTAL_PENDING.ordinal());
        notProcessedGroupCell.setCellValue("Não Processados");
        CellStyle notProcessedGroupCellStyle = getNotProcessedGroupCellStyle(workbook);
        notProcessedGroupCell.setCellStyle(notProcessedGroupCellStyle);
        sheet.addMergedRegion(new CellRangeAddress(SECOND_ROW, SECOND_ROW, FIRST_COL, 6));
        sheet.addMergedRegion(new CellRangeAddress(SECOND_ROW, SECOND_ROW, ResultsByHFSummary.TOTAL_PENDING.ordinal(),
                ResultsByHFSummary.NOT_PROCESSED_DUPLICATED_REQUEST_ID.ordinal()));

        // Headers
        Row headersRow = sheet.createRow(THIRD_ROW);
        for (ResultsByHFSummary byHfSummary : ResultsByHFSummary.values()) {
            Cell headerCell = headersRow.createCell(byHfSummary.ordinal());
            headerCell.setCellValue(byHfSummary.header());
            headerCell.setCellStyle(headerCellStyle);
        }
        // Apply not processed group style
        for (int j = ResultsByHFSummary.TOTAL_PENDING
                .ordinal(); j <= ResultsByHFSummary.NOT_PROCESSED_DUPLICATED_REQUEST_ID.ordinal(); j++) {
            sheet.getRow(THIRD_ROW).getCell(j).setCellStyle(notProcessedGroupCellStyle);
        }

        AtomicInteger counter = new AtomicInteger(3);
        viralLoaderResultSummary.stream().forEach(viralResult -> {
            Row row = sheet.createRow(counter.getAndIncrement());
            createViralResultSummaryRow(row, viralResult);
        });

        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
        sheet.autoSizeColumn(2);
        sheet.autoSizeColumn(3);
        sheet.autoSizeColumn(4);
        sheet.autoSizeColumn(5);
        sheet.autoSizeColumn(6);
        sheet.autoSizeColumn(7);
        sheet.autoSizeColumn(8);
        sheet.autoSizeColumn(9);
        sheet.autoSizeColumn(10);
        sheet.autoSizeColumn(11);
    }

    /**
     * Creates the title row
     * 
     * @param workbook The workbook
     * @param sheet    The worksheet
     * @param title    Title
     * @param lastCol  The last column used in the worksheet
     */
    private void createFirstRow(Workbook workbook, Sheet sheet, String title, int lastCol) {
        Row headerRow = sheet.createRow(FIRST_ROW);
        CellStyle headerCellStyle = getHeaderCellStyle(workbook);
        Cell cell = headerRow.createCell(0);
        cell.setCellValue(title);
        sheet.addMergedRegion(new CellRangeAddress(FIRST_ROW, FIRST_ROW, FIRST_COL, lastCol));
        cell.setCellStyle(headerCellStyle);
    }

    private CellStyle getBoldStyle(Workbook workbook) {
        CellStyle headerStyle = workbook.createCellStyle();
        XSSFFont font = ((XSSFWorkbook) workbook).createFont();
        font.setBold(true);
        headerStyle.setFont(font);
        return headerStyle;
    }

    private CellStyle dictionaryHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        XSSFFont font = ((XSSFWorkbook) wb).createFont();
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        return style;
    }

    private CellStyle getHeaderCellStyle(Workbook workbook) {
        CellStyle headerStyle = workbook.createCellStyle();
        XSSFFont font = ((XSSFWorkbook) workbook).createFont();
        font.setBold(true);
        headerStyle.setFont(font);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        return headerStyle;
    }

    private CellStyle getPercentCellStyle(Workbook workbook) {
        CellStyle percent = workbook.createCellStyle();
        DataFormat df = workbook.createDataFormat();
        percent.setDataFormat(df.getFormat("0%"));
        return percent;
    }

    private CellStyle getBoldPercentCellStyle(Workbook workbook) {
        CellStyle boldPercent = workbook.createCellStyle();
        DataFormat df = workbook.createDataFormat();
        boldPercent.cloneStyleFrom(getBoldStyle(workbook));
        boldPercent.setDataFormat(df.getFormat("0%"));
        return boldPercent;
    }

    private CellStyle getNotProcessedGroupCellStyle(Workbook workbook) {
        CellStyle cellStyle = workbook.createCellStyle();
        cellStyle.cloneStyleFrom(getHeaderCellStyle(workbook));
        // cellStyle.setFillBackgroundColor(IndexedColors.BLACK.index);
        // cellStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        // cellStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        return cellStyle;
    }

    private void createViralResultSummaryRow(Row row, LabResultSummary viralLoaderResult) {
        for (ResultsByHFSummary byHfSummary : ResultsByHFSummary.values()) {
            Cell cell = row.createCell(byHfSummary.ordinal());
            switch (byHfSummary) {
                case PROVINCE:
                    cell.setCellValue(viralLoaderResult.getRequestingProvinceName());
                    break;
                case DISTRICT:
                    cell.setCellValue(viralLoaderResult.getRequestingDistrictName());
                    break;
                case HEALTH_FACILITY_CODE:
                    cell.setCellValue(StringUtils.center(viralLoaderResult.getHealthFacilityLabCode(), 11, " "));
                    break;
                case HEALTH_FACILITY_NAME:
                    cell.setCellValue(viralLoaderResult.getFacilityName());
                    break;
                case TYPE_OF_RESULT:
                    cell.setCellValue(viralLoaderResult.getTypeOfResult());
                    break;
                case TOTAL_RECEIVED:
                    cell.setCellValue(viralLoaderResult.getTotalReceived());
                    break;
                case TOTAL_PROCESSED:
                    cell.setCellValue(viralLoaderResult.getProcessed());
                    break;
                case TOTAL_PENDING:
                    cell.setCellValue(viralLoaderResult.getTotalPending());
                    break;
                case NOT_PROCESSED_INVALID_RESULT:
                    cell.setCellValue(viralLoaderResult.getNotProcessedInvalidResult());
                    break;
                case NOT_PROCESSED_NID_NOT_FOUND:
                    cell.setCellValue(viralLoaderResult.getNotProcessedNidNotFount());
                    break;
                case NOT_PROCESSED_DUPLICATED_NID:
                    cell.setCellValue(viralLoaderResult.getNotProcessedDuplicateNid());
                    break;
                case NOT_PROCESSED_DUPLICATED_REQUEST_ID:
                    cell.setCellValue(viralLoaderResult.getNotProcessedDuplicatedRequestId());
                    break;
                default:
                    break;
            }
        }
    }

    private void createReceivedByNIDRow(Row row, LabResults viralLoaderResult) {

        for (ResultsReceivedByNid byNID : ResultsReceivedByNid.values()) {
            Cell cell = row.createCell(byNID.ordinal());
            switch (byNID) {
                case REQUEST_ID:
                    cell.setCellValue(viralLoaderResult.getRequestId());
                    break;

                case TYPE_OF_RESULT:
                    cell.setCellValue(viralLoaderResult.getTypeOfResult());
                    break;
                case NID:
                    cell.setCellValue(viralLoaderResult.getNID());
                    break;
                case DISTRICT:
                    cell.setCellValue(viralLoaderResult.getRequestingDistrictName());
                    break;
                case HEALTH_FACILITY_CODE:
                    cell.setCellValue(viralLoaderResult.getHealthFacilityLabCode());
                    break;
                case HEALTH_FACILITY_NAME:
                    cell.setCellValue(viralLoaderResult.getRequestingFacilityName());
                    break;
                case CREATED_AT:
                    cell.setCellValue(viralLoaderResult.getCreatedAt().format(DATE_FORMAT));

                    break;
                case UPDATED_AT:
                    cell.setCellValue(viralLoaderResult.getUpdatedAt() != null
                            ? viralLoaderResult.getUpdatedAt().format(DATE_FORMAT)
                            : "");
                    break;
                case VIRAL_RESULT_STATUS:
                    cell.setCellValue(
                            messageSource.getMessage("disa.viraLoadStatus." + viralLoaderResult.getViralLoadStatus(),
                                    new String[] {}, Locale.getDefault()));
                    break;
                case NOT_PROCESSING_CAUSE:
                    String cellValue = "";
                    if (viralLoaderResult.getNotProcessingCause() != null) {
                        cellValue = messageSource.getMessage(
                                "disa.notProcessingCause." + viralLoaderResult.getNotProcessingCause(), new String[] {},
                                Locale.getDefault());
                    }
                    cell.setCellValue(cellValue);
                    break;
                case OBS:
                    cell.setCellValue(viralLoaderResult.getNotProcessingCause() != null
                            && viralLoaderResult.getNotProcessingCause().trim().equals("NID_NOT_FOUND")
                            && viralLoaderResult.getViralLoadStatus().equals(ViralLoadStatus.PROCESSED.name())
                                    ? "Reprocessado apos a correcao do NID"
                                    : " ");
                    break;
                default:
                    break;
            }

        }
    }

    private void createPendingViralResultSummaryRow(Row row,
            PendingHealthFacilitySummary pendingViralResultSummary) {

        for (ResultsPendingByUs pending : ResultsPendingByUs.values()) {
            Cell cell = row.createCell(pending.ordinal());
            switch (pending) {
                case PROVINCE:
                    cell.setCellValue(pendingViralResultSummary.getRequestingProvinceName());
                    break;
                case DISTRICT:
                    cell.setCellValue(pendingViralResultSummary.getRequestingDistrictName());
                    break;
                case US_CODE:
                    cell.setCellValue(pendingViralResultSummary.getHealthFacilityLabCode());
                    break;
                case US_NAME:
                    cell.setCellValue(pendingViralResultSummary.getFacilityName());
                    break;
                case TOTAL_PENDING:
                    cell.setCellValue(pendingViralResultSummary.getTotalPending());
                    break;
                case LAST_SYNC:
                    cell.setCellValue(pendingViralResultSummary.getLastSyncDate() != null ? pendingViralResultSummary
                            .getLastSyncDate().toLocalDate().format(DATE_FORMAT) : "");
                    break;
                default:
                    break;
            }
        }

    }

    private void createUnsyncronizedViralResultRow(Row row, LabResults viralLoaderResult) {

        for (ResultsPendingByNid pendingByNid : ResultsPendingByNid.values()) {
            Cell cell = row.createCell(pendingByNid.ordinal());
            switch (pendingByNid) {
                case REQUEST_ID:
                    cell.setCellValue(viralLoaderResult.getRequestId());
                    break;
                case NID:
                    cell.setCellValue(viralLoaderResult.getNID());
                    break;
                case DISTRICT:
                    cell.setCellValue(viralLoaderResult.getRequestingDistrictName());
                    break;
                case HEALTH_FACILITY_CODE:
                    cell.setCellValue(viralLoaderResult.getHealthFacilityLabCode());
                    break;
                case HEALTH_FACILITY_NAME:
                    cell.setCellValue(viralLoaderResult.getRequestingFacilityName());
                    break;
                case SENT_DATE:
                    cell.setCellValue(
                            viralLoaderResult.getCreatedAt().toLocalDate().format(DATE_FORMAT));
                    break;
                case STATUS:
                    cell.setCellValue(messageSource
                            .getMessage("disa.viraLoadStatus." + viralLoaderResult.getViralLoadStatus(),
                                    new String[] {}, Locale.getDefault()));
                    break;
                default:
                    break;
            }
        }

    }

    private void createStatResultRow(Workbook workbook, Row row, String province, String district,
            ViralResultStatistics viralResultStatistics) {

        for (ResultsByDistrictSummary r : ResultsByDistrictSummary.values()) {
            Cell cell = row.createCell(r.ordinal());
            switch (r) {
                case PROVINCE:
                    cell.setCellValue(province);
                    break;
                case DISTRICT:
                    cell.setCellValue(district);
                    break;
                case TYPE_OF_RESULT:
                    cell.setCellValue(viralResultStatistics.getTypeOfResult());
                    break;
                case TOTAL_PROCESSED:
                    cell.setCellValue(viralResultStatistics.getProcessed());
                    break;
                case PERCENTAGE_PROCESSED:
                    cell.setCellStyle(getPercentCellStyle(workbook));
                    cell.setCellValue(viralResultStatistics.getProcessedPercentage());
                    break;
                case TOTAL_PENDING:
                    cell.setCellValue(viralResultStatistics.getPending());
                    break;
                case PERCENTAGE_PENDING:
                    cell.setCellStyle(getPercentCellStyle(workbook));
                    cell.setCellValue(viralResultStatistics.getPendingPercentage());
                    break;
                case NOT_PROCESSED_INVALID_RESULT:
                    cell.setCellValue(viralResultStatistics.getNoProcessedInvalidResult());
                    break;
                case PERCENTAGE_NOT_PROCESSED_INVALID_RESULT:
                    cell.setCellStyle(getPercentCellStyle(workbook));
                    cell.setCellValue(viralResultStatistics.getNoProcessedNoResultPercentage());
                    break;
                case NOT_PROCESSED_NID_NOT_FOUND:
                    cell.setCellValue(viralResultStatistics.getNoProcessedNidNotFound());
                    break;
                case PERCENTAGE_NOT_PROCESSED_NID_NOT_FOUND:
                    cell.setCellStyle(getPercentCellStyle(workbook));
                    cell.setCellValue(viralResultStatistics.getNoProcessedNidNotFoundPercentage());
                    break;
                case NOT_PROCESSED_DUPLICATED_NID:
                    cell.setCellValue(viralResultStatistics.getNotProcessedDuplicateNid());
                    break;
                case PERCENTAGE_NOT_PROCESSED_DUPLICATED_NID:
                    cell.setCellStyle(getPercentCellStyle(workbook));
                    cell.setCellValue(viralResultStatistics.getNotProcessedDuplicateNidPercentage());
                    break;
                case NOT_PROCESSED_DUPLICATED_REQUEST_ID:
                    cell.setCellValue(viralResultStatistics.getNotProcessedDuplicatedReqId());
                    break;
                case PERCENTAGE_NOT_PROCESSED_DUPLICATED_REQUEST_ID:
                    cell.setCellStyle(getPercentCellStyle(workbook));
                    cell.setCellValue(viralResultStatistics.getNotProcessedDuplicatedReqIdPercentage());
                    break;
                case TOTAL_RECEIVED:
                    cell.setCellValue(viralResultStatistics.getTotal());
                    break;
                default:
                    break;
            }
        }
    }

    private void createStatLastResultRow(Workbook workbook, Row row, ViralResultStatistics viralResultStatistics) {

        for (ResultsByDistrictSummary r : ResultsByDistrictSummary.values()) {
            Cell cell = row.createCell(r.ordinal());
            switch (r) {
                case PROVINCE:
                    cell.setCellValue("Total");
                    cell.setCellStyle(getTotalsCellStyle(workbook));
                    break;
                case TOTAL_PROCESSED:
                    cell.setCellValue(viralResultStatistics.getProcessed());
                    cell.setCellStyle(getBoldStyle(workbook));
                    break;
                case PERCENTAGE_PROCESSED:
                    cell.setCellValue(viralResultStatistics.getProcessedPercentage());
                    cell.setCellStyle(getBoldPercentCellStyle(workbook));
                    break;
                case TOTAL_PENDING:
                    cell.setCellValue(viralResultStatistics.getPending());
                    cell.setCellStyle(getBoldStyle(workbook));
                    break;
                case PERCENTAGE_PENDING:
                    cell.setCellValue(viralResultStatistics.getPendingPercentage());
                    cell.setCellStyle(getBoldPercentCellStyle(workbook));
                    break;
                case NOT_PROCESSED_INVALID_RESULT:
                    cell.setCellValue(viralResultStatistics.getNoProcessedInvalidResult());
                    cell.setCellStyle(getBoldStyle(workbook));
                    break;
                case PERCENTAGE_NOT_PROCESSED_INVALID_RESULT:
                    cell.setCellValue(viralResultStatistics.getNoProcessedNoResultPercentage());
                    cell.setCellStyle(getBoldPercentCellStyle(workbook));
                    break;
                case NOT_PROCESSED_NID_NOT_FOUND:
                    cell.setCellValue(viralResultStatistics.getNoProcessedNidNotFound());
                    cell.setCellStyle(getBoldStyle(workbook));
                    break;
                case PERCENTAGE_NOT_PROCESSED_NID_NOT_FOUND:
                    cell.setCellValue(viralResultStatistics.getNoProcessedNidNotFoundPercentage());
                    cell.setCellStyle(getBoldPercentCellStyle(workbook));
                    break;
                case NOT_PROCESSED_DUPLICATED_NID:
                    cell.setCellValue(viralResultStatistics.getNotProcessedDuplicateNid());
                    cell.setCellStyle(getBoldStyle(workbook));
                    break;
                case PERCENTAGE_NOT_PROCESSED_DUPLICATED_NID:
                    cell.setCellValue(viralResultStatistics.getNotProcessedDuplicateNidPercentage());
                    cell.setCellStyle(getBoldPercentCellStyle(workbook));
                    break;
                case NOT_PROCESSED_DUPLICATED_REQUEST_ID:
                    cell.setCellValue(viralResultStatistics.getNotProcessedDuplicatedReqId());
                    cell.setCellStyle(getBoldStyle(workbook));
                    break;
                case PERCENTAGE_NOT_PROCESSED_DUPLICATED_REQUEST_ID:
                    cell.setCellValue(viralResultStatistics.getNotProcessedDuplicatedReqIdPercentage());
                    cell.setCellStyle(getBoldPercentCellStyle(workbook));
                    break;
                case TOTAL_RECEIVED:
                    cell.setCellValue(viralResultStatistics.getTotal());
                    cell.setCellStyle(getBoldStyle(workbook));
                    break;
                default:
                    break;
            }
        }
    }

    private CellStyle getTotalsCellStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setAlignment(HorizontalAlignment.RIGHT);
        XSSFFont font = ((XSSFWorkbook) workbook).createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

}
