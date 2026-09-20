package mx.salvador.wabot.webhook;

import com.google.api.services.drive.model.File;
import java.util.*;

/** Conservative name matching; ambiguous/manual files never become deletion targets. */
final class DriveNoteInventory {
    record Result(Map<String, List<String>> matches, Set<String> ambiguous, List<String> unassigned) {}

    static Result match(List<String> songs, List<File> files, MusicWorkState state, String folder) {
        var matches = new LinkedHashMap<String, List<String>>();
        var ambiguous = new HashSet<String>();
        var unassigned = new ArrayList<String>();
        songs.forEach(song -> matches.put(song, new ArrayList<>()));
        for (var file : files) {
            if (!document(file)) continue;
            var candidates = songs.stream().filter(song -> state.copies(song).stream()
                    .anyMatch(copy -> copy.folder().equals(folder) && copy.id().equals(file.getId()))).toList();
            if (candidates.isEmpty()) {
                String name = normalized(file.getName()).replaceFirst("^borrador(?: de acordes)? +", "");
                candidates = songs.stream().filter(song -> {
                    String title = normalized(MusicWorkState.key(song));
                    return !title.isBlank() && (name.equals(title) || name.startsWith(title + " "));
                }).toList();
            }
            if (candidates.size() == 1) matches.get(candidates.get(0)).add(file.getName());
            else { unassigned.add(file.getName()); ambiguous.addAll(candidates); }
        }
        var immutable = new LinkedHashMap<String, List<String>>();
        matches.forEach((song, names) -> immutable.put(song, List.copyOf(names)));
        return new Result(Map.copyOf(immutable), Set.copyOf(ambiguous), List.copyOf(unassigned));
    }

    static boolean document(File file) {
        String mime = Objects.toString(file.getMimeType(), "");
        return Set.of("application/pdf", "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.google-apps.document").contains(mime);
    }

    private static String normalized(String text) {
        return java.text.Normalizer.normalize(Objects.toString(text, ""), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceFirst("\\.(pdf|docx?|m4a|mp3)$", "")
                .replaceAll("[^a-z0-9]+", " ").strip();
    }
}
