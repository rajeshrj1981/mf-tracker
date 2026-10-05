package com.mftracker;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Locally reads CAMS V3.5 detailed CAS PDFs using their positioned transaction columns. */
@Service
public class CasImportService {
    private static final Pattern TRANSACTION_DATE = Pattern.compile("^\\d{1,2}-[A-Za-z]{3}-\\d{4}$");
    private static final Pattern SCHEME_HEADER = Pattern.compile(
            "(?i)\\b[A-Z0-9]{2,}\\s*-\\s*(.+?)\\s*\\(\\s*Non\\b[\\s\\S]{0,160}?\\bDemat\\s*\\)");
    private static final Pattern FUND_HOUSE_HEADER = Pattern.compile("(?i)^(.+?)\\s+Mutual Fund$");
    private static final Pattern NUMBER = Pattern.compile("-?\\d[\\d,]*(?:\\.\\d+)?");

    // CAMS V3.5 PDF column boundaries (PDF points). Text positions avoid the run-together
    // numeric columns produced by ordinary text extraction.
    private static final float DATE_END = 70f;
    private static final float DESCRIPTION_END = 340f;
    // The first amount digit can sit a few points left of the visual column boundary.
    private static final float AMOUNT_START = 330f;
    private static final float AMOUNT_END = 390f;
    private static final float UNITS_START = 390f;
    private static final float UNITS_END = 455f;
    private static final float NAV_START = 455f;
    private static final float NAV_END = 535f;

    public record CasTransaction(String fundHouse, String schemeName, LocalDate date, BigDecimal amount, BigDecimal units,
                                 BigDecimal nav, String transactionType) { }
    public record Preview(List<CasTransaction> transactions, String message) { }

    private record Positioned(int page, float x, float y, String text) { }
    private record LineKey(int page, int yBin) { }
    private record TextLine(int page, int yBin, List<Positioned> chars) {
        String cell(float from, float to) {
            StringBuilder out = new StringBuilder();
            chars.stream().filter(c -> c.x() >= from && c.x() < to)
                    .sorted(Comparator.comparing(Positioned::x)).forEach(c -> out.append(c.text()));
            return out.toString().replaceAll("\\s+", " ").trim();
        }
        String full() { return cell(0, Float.MAX_VALUE); }
    }

    public Preview parse(MultipartFile file, String password) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Choose a CAMS CAS PDF to upload.");
        if (file.getSize() > 20 * 1024 * 1024) throw new IllegalArgumentException("CAS PDF must be smaller than 20 MB.");

        List<Positioned> chars = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(file.getBytes(), password == null ? "" : password)) {
            PDFTextStripper stripper = new PDFTextStripper() {
                { setSortByPosition(true); }
                @Override protected void writeString(String text, List<TextPosition> positions) {
                    int page = getCurrentPageNo();
                    for (TextPosition position : positions) {
                        // CAMS overlays rotated watermark text across some rows; exclude it before grouping transaction characters.
                        if (Math.abs(position.getDir()) < 1f)
                            chars.add(new Positioned(page, position.getXDirAdj(), position.getYDirAdj(), position.getUnicode()));
                    }
                }
            };
            stripper.getText(document);
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            throw new IllegalArgumentException("That PDF password did not work. Check it and try again.");
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the PDF. Upload a text-based CAMS detailed CAS.");
        }
        if (chars.isEmpty()) throw new IllegalArgumentException("No selectable text found. Scanned CAS files are not supported.");

        List<TextLine> lines = groupLines(chars);
        if (lines.stream().noneMatch(line -> line.full().contains("Consolidated Account Statement")))
            throw new IllegalArgumentException("This does not look like a supported CAMS Consolidated Account Statement PDF.");
        return new Preview(parseTransactions(lines), "Review the parsed CAMS purchase rows before importing. The PDF and password were processed locally.");
    }

    private List<CasTransaction> parseTransactions(List<TextLine> lines) {
        List<CasTransaction> transactions = new ArrayList<>();
        String fundHouse = null;
        String scheme = null;
        List<String> headerWindow = new ArrayList<>();
        for (TextLine line : lines) {
            String all = line.full();
            Matcher houseMatch = FUND_HOUSE_HEADER.matcher(all);
            if (houseMatch.matches()) fundHouse = all.trim();

            headerWindow.add(all);
            if (headerWindow.size() > 5) headerWindow.remove(0);
            Matcher schemeMatch = SCHEME_HEADER.matcher(String.join(" ", headerWindow));
            String latestScheme = null;
            while (schemeMatch.find()) latestScheme = cleanScheme(schemeMatch.group(1));
            if (latestScheme != null) scheme = latestScheme;

            String dateCell = line.cell(0, DATE_END);
            if (scheme == null || !TRANSACTION_DATE.matcher(dateCell).matches()) continue;
            String description = line.cell(DATE_END, DESCRIPTION_END);
            if (description.isBlank()) {
                // Some CAMS rows place the description one PDF point above the date/numeric
                // baseline. Recover only that adjacent description cell, rather than merging
                // complete lines (which can pull in watermark text from elsewhere on the page).
                description = lines.stream()
                        .filter(other -> other != line && other.page() == line.page() && Math.abs(other.yBin() - line.yBin()) <= 2)
                        .map(other -> other.cell(DATE_END, DESCRIPTION_END))
                        .filter(text -> text.toLowerCase(Locale.ROOT).contains("purchase") || text.toLowerCase(Locale.ROOT).contains("stamp duty"))
                        .findFirst().orElse("");
            }
            String lowerDescription = description.toLowerCase(Locale.ROOT);
            BigDecimal amount = number(line.cell(AMOUNT_START, AMOUNT_END));

            if (lowerDescription.contains("stamp duty")) {
                if (amount != null && !transactions.isEmpty()) {
                    int last = transactions.size() - 1;
                    CasTransaction prior = transactions.get(last);
                    if (prior.date().equals(parseDate(dateCell)))
                        transactions.set(last, new CasTransaction(prior.fundHouse(), prior.schemeName(), prior.date(),
                                prior.amount().add(amount), prior.units(), prior.nav(), prior.transactionType()));
                }
                continue;
            }
            // Do not import switches, redemptions, dividends, opening balances, or non-purchase events.
            if (!lowerDescription.contains("purchase") || amount == null || amount.signum() <= 0) continue;
            BigDecimal units = number(line.cell(UNITS_START, UNITS_END));
            BigDecimal nav = number(line.cell(NAV_START, NAV_END));
            LocalDate date = parseDate(dateCell);
            if (date == null || nav == null || nav.signum() <= 0 || units == null || units.signum() <= 0) continue;
            transactions.add(new CasTransaction(fundHouse, scheme, date, amount, units, nav, description));
        }
        return transactions;
    }

    private static List<TextLine> groupLines(List<Positioned> chars) {
        Map<LineKey, List<Positioned>> grouped = new TreeMap<>(Comparator.comparingInt(LineKey::page).thenComparingInt(LineKey::yBin));
        // CAMS draws some columns on slightly different baselines; a one-point line bucket
        // keeps transaction rows separate. parseTransactions joins a missing description
        // cell to an adjacent baseline only when that date row needs it.
        for (Positioned c : chars) grouped.computeIfAbsent(new LineKey(c.page(), Math.round(c.y())), k -> new ArrayList<>()).add(c);
        return grouped.entrySet().stream().map(e -> new TextLine(e.getKey().page(), e.getKey().yBin(), e.getValue())).toList();
    }

    private static String cleanScheme(String scheme) {
        return scheme.replaceAll("\\s+", " ").replaceAll("\\s*[-,;:]\\s*$", "").trim();
    }

    private static BigDecimal number(String text) {
        Matcher m = NUMBER.matcher(text);
        if (!m.find()) return null;
        try { return new BigDecimal(m.group().replace(",", "")); }
        catch (NumberFormatException e) { return null; }
    }

    private static LocalDate parseDate(String value) {
        try { return LocalDate.parse(value, DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.ENGLISH)); }
        catch (DateTimeParseException e) { return null; }
    }
}
