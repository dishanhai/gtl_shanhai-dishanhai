package com.dishanhai.gt_shanhai.api.whitehole;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Named model extensions kept by the caller. Separate API objects have independent registrations. */
public final class WhiteHoleAPI {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private final Map<String, SpacetimeModel> spacetimes = new LinkedHashMap<>();
    private final Map<String, RadiationModel> radiation = new LinkedHashMap<>();

    public WhiteHoleAPI() {
        for (StandardSpacetime model : StandardSpacetime.values()) registerSpacetimeModel(builtinId(model), model);
        for (RadiationModel.Profile model : RadiationModel.Profile.values()) registerRadiationModel(builtinId(model), model);
    }

    public WhiteHoleEngine create(WhiteHoleParameters parameters) {
        return new WhiteHoleEngine(parameters);
    }

    public void registerSpacetimeModel(String id, SpacetimeModel model) {
        register(spacetimes, id, model);
    }

    public void registerRadiationModel(String id, RadiationModel model) {
        register(radiation, id, model);
    }

    public Map<String, SpacetimeModel> spacetimeModels() {
        return Map.copyOf(spacetimes);
    }

    public Map<String, RadiationModel> radiationModels() {
        return Map.copyOf(radiation);
    }

    private static <T> void register(Map<String, T> table, String id, T model) {
        Objects.requireNonNull(model);
        if (id == null || !IDENTIFIER.matcher(id).matches()) throw new IllegalArgumentException("expected namespace:path");
        if (table.putIfAbsent(id, model) != null) throw new IllegalArgumentException("duplicate model id: " + id);
    }

    private static String builtinId(Enum<?> model) {
        return "gt_shanhai:" + model.name().toLowerCase(Locale.ROOT);
    }
}
