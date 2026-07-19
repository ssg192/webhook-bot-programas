package mx.salvador.wabot.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

public final class Fechas {

    public static final ZoneId CDMX = ZoneId.of("America/Mexico_City");

    private static final String[] MESES = {
            "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
            "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"};

    private Fechas() {}

    /**
     * Proximo domingo en CDMX. Si hoy es domingo, regresa hoy.
     */
    public static LocalDate proximoDomingo() {
        return LocalDate.now(CDMX).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
    }

    public static String anio(LocalDate d) {
        return String.valueOf(d.getYear());
    }

    public static String mes(LocalDate d) {
        return MESES[d.getMonthValue() - 1];
    }

    /** Nombre tipo "19 de Julio 2026", como los docs del equipo. */
    public static String nombreCarpeta(LocalDate d) {
        return "%d de %s %d".formatted(d.getDayOfMonth(), mes(d), d.getYear());
    }
}