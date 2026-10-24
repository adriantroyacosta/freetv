package io.freemytv;

import java.util.HashMap;
import java.util.Map;

/**
 * Spanish (Mexico): each English text of Ui.tr → its translation. A text that's missing here shows in English, so
 * add new ones to Ui.tr and here. Texts with numbers in them use Ui.tr's three-language form instead.
 */
final class Es {
    private Es() {}

    static final Map<String, String> TEXT = new HashMap<>();

    private static void put(String english, String spanish) {
        TEXT.put(english, spanish);
    }

    static {
        // Home screen
        put("For You", "Para ti");
        put("Live TV", "TV en vivo");
        put("HDMI Inputs", "Entradas HDMI");
        put("Settings", "Configuración");
        put("All Apps", "Todas las apps");
        put("Home Settings", "Ajustes de inicio");
        put("TV Settings", "Ajustes de la TV");
        put("Inputs", "Entradas");
        put("Shortcuts", "Accesos directos");
        put("Couldn't open ", "No se pudo abrir ");
        put("Series", "Serie");
        put("Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…",
                "La pantalla de inicio de Free My TV tuvo un problema.\nRegresando a la pantalla de inicio original de la TV…");

        // Weather and prices
        put("Weather", "Clima");
        put("No weather data right now.", "Sin datos del clima por ahora.");
        put("Sunrise ", "Amanecer ");
        put("Sunset ", "Atardecer ");
        put("Crypto", "Criptomonedas");
        put("24h change", "variación en 24 h");
        put("prices from ", "precios de ");
        put("Dollar", "Dólar");

        // Settings
        put("Your TV's home screen and screensaver", "Pantalla de inicio y protector de pantalla de tu TV");
        put("Home Screen", "Pantalla de inicio");
        put("Screensaver", "Protector de pantalla");
        put("Preview", "Ver ahora");
        put("Starting…", "Iniciando…");
        put("On the TV: tick “Always allow” and press OK.", "En la TV: marca “Permitir siempre” y presiona OK.");
        put("RECOMMENDATIONS", "RECOMENDACIONES");
        put("No TMDB key: see the README (config.properties).", "Sin clave de TMDB: consulta el README (config.properties).");
        put("Error: ", "Error: ");
        put("Fetching titles…", "Buscando títulos…");
        put("This product uses the TMDB API but is not endorsed or certified by TMDB.",
                "Este producto usa la API de TMDB, pero TMDB no lo respalda ni lo certifica.");
        put("COMPARISON", "COMPARACIÓN");
        put("Home button", "Botón Inicio");
        put("Free memory", "Memoria libre");
        put("Home screen memory", "Memoria de la pantalla de inicio");
        put("NOTICES", "AVISOS");
        put("None yet.", "Ninguno todavía.");
        put("No ads, with the new screensaver", "Sin anuncios, con el nuevo protector de pantalla");
        put("The TV's factory home screen", "La pantalla de inicio de fábrica de la TV");
        put("Switching takes a few seconds; the screen blinks while it measures.",
                "El cambio tarda unos segundos; la pantalla parpadea mientras mide.");
        put("now", "ahora");

        // Switching modes
        put("Connecting…", "Conectando…");
        put("Accept “Allow USB debugging?” on the TV (tick “Always allow”).",
                "Acepta “¿Permitir depuración por USB?” en la TV (marca “Permitir siempre”).");
        put("Measuring the original home screen…", "Midiendo la pantalla de inicio original…");
        put("Switching the home screen and screensaver…", "Cambiando la pantalla de inicio y el protector de pantalla…");
        put("Android didn't accept the new home screen", "Android no aceptó la nueva pantalla de inicio");
        put("Turning off apps (", "Desactivando apps (");
        put("Measuring Free My TV…", "Midiendo Free My TV…");
        put("Couldn't turn on Free My TV: ", "No se pudo activar Free My TV: ");
        put("Turning the apps back on…", "Reactivando las apps…");
        put("Turning apps back on (", "Reactivando apps (");
        put("Restoring the original home screen…", "Restaurando la pantalla de inicio original…");
        put("Original mode: the factory home screen, screensaver and apps are back.",
                "Modo original: volvieron la pantalla de inicio, el protector de pantalla y las apps de fábrica.");
        put("the original home screen", "la pantalla de inicio original");
        put("Original mode, but I couldn't turn back on: ", "Modo original, pero no pude reactivar: ");
        put("Couldn't go back to the original: ", "No se pudo regresar al original: ");
        put("Free My TV's home screen crashed 3 times in a row; I switched back to the original one.",
                "La pantalla de inicio de Free My TV falló 3 veces seguidas; regresé a la original.");
        put("the home screen", "la pantalla de inicio");
        put("the screensaver", "el protector de pantalla");
        put("Couldn't check Free My TV: ", "No se pudo revisar Free My TV: ");
        put("The TV update turned back on ", "La actualización de la TV volvió a activar ");
        put("Turned off what was missing: ", "Desactivé lo que faltaba: ");
        put("The TV was updated; Free My TV is still on.", "La TV se actualizó; Free My TV sigue activo.");
    }
}
