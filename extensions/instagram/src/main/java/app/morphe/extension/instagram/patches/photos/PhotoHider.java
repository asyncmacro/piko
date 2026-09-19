/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.photos;

import android.view.View;
import android.widget.ImageView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.instagram.utils.Pref;

/**
 * Hides feed/timeline photos behind a blank placeholder until tapped.
 *
 * <p>Called at the entry of {@code IgProgressImageView.setUrl}. All app-class
 * access is reflective (obfuscation-proof): only {@code getIgImageView},
 * {@code setPlaceHolderColor}, {@code setUrl} and {@code ImageUrl.getUrl}
 * names are used, all verified unobfuscated on the pinned version.
 *
 * <p>Fail-open everywhere: any failure returns {@code false} so the original
 * {@code setUrl} runs and the feed never breaks.
 */
@SuppressWarnings("unused")
public class PhotoHider {
    private static final int PLACEHOLDER_COLOR = 0xFF2A2A2A;

    /** View -> stashed setUrl argument. Overwritten on every rebind (recycling-safe). */
    private static final Map<View, Object> STASH =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** Views that already have our click listener (app may replace it on rebind). */
    private static final Map<View, Boolean> LISTENER_SET =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** Image URL strings already tapped: survive scroll/recycle, so tapped photos stay visible. */
    private static final Set<String> REVEALED = Collections.synchronizedSet(new HashSet<>());

    /**
     * @param view the IgProgressImageView being bound (as {@link View}, no compile dep).
     * @param urlWrapper the {@code setUrl} argument (obfuscated holder of the ImageUrl).
     * @return true if the call was consumed (photo hidden), false to run the original setUrl.
     */
    public static boolean hideUntilTap(View view, Object urlWrapper) {
        try {
            if (view == null || urlWrapper == null) return false;
            if (!Pref.ultraHidePhotos()) return false;
            String key = photoKey(urlWrapper);
            if (key != null && REVEALED.contains(key)) return false;
            STASH.put(view, urlWrapper);
            clearImage(view);
            setPlaceholder(view);
            // Invisible keeps layout bounds while guaranteeing no stale bitmap shows.
            view.setVisibility(View.INVISIBLE);
            ensureClickListener(view);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void reveal(View view) {
        try {
            Object stashed = STASH.remove(view);
            if (stashed == null) {
                view.setVisibility(View.VISIBLE);
                return;
            }
            String key = photoKey(stashed);
            if (key != null) REVEALED.add(key);
            view.setVisibility(View.VISIBLE);
            Method setUrl = findSetUrl(view, stashed);
            setUrl.invoke(view, stashed);
        } catch (Exception ignored) {
            try {
                view.setVisibility(View.VISIBLE);
            } catch (Exception ignored2) {
                // Nothing left to do; feed stays usable.
            }
        }
    }

    private static Method findSetUrl(View view, Object stashed) throws NoSuchMethodException {
        try {
            return view.getClass().getMethod("setUrl", stashed.getClass());
        } catch (NoSuchMethodException e) {
            Method declared = view.getClass().getDeclaredMethod("setUrl", stashed.getClass());
            declared.setAccessible(true);
            return declared;
        }
    }

    private static void ensureClickListener(View view) {        try {
            if (LISTENER_SET.containsKey(view)) return;
            LISTENER_SET.put(view, Boolean.TRUE);
            view.setOnClickListener(PhotoHider::reveal);
            view.setClickable(true);
        } catch (Exception ignored) {
            // Tapping falls back to the app's own click handling (detail viewer).
        }
    }

    private static void clearImage(View view) {
        try {
            Method getter = view.getClass().getMethod("getIgImageView");
            Object inner = getter.invoke(view);
            if (inner instanceof ImageView) {
                ((ImageView) inner).setImageDrawable(null);
            }
        } catch (Exception ignored) {
            // INVISIBLE (set by caller) already guarantees nothing stale shows.
        }
    }

    private static void setPlaceholder(View view) {
        try {
            Method setter = view.getClass().getMethod("setPlaceHolderColor", int.class);
            setter.invoke(view, PLACEHOLDER_COLOR);
        } catch (Exception ignored) {
            try {
                view.setBackgroundColor(PLACEHOLDER_COLOR);
            } catch (Exception ignored2) {
                // INVISIBLE (set by caller) already guarantees a blank box.
            }
        }
    }

    /** Extracts the image URL string from the obfuscated setUrl argument, or null. */
    private static String photoKey(Object urlWrapper) {
        try {
            for (Field field : urlWrapper.getClass().getDeclaredFields()) {
                if (!field.getType().getName().contains("typedurl")) continue;
                field.setAccessible(true);
                Object imageUrl = field.get(urlWrapper);
                if (imageUrl == null) continue;
                try {
                    Object url = imageUrl.getClass().getMethod("getUrl").invoke(imageUrl);
                    if (url instanceof String) return (String) url;
                } catch (NoSuchMethodException ignored) {
                    // Try the next typedurl field, if any.
                }
            }
        } catch (Exception ignored) {
            // Best-effort only; callers handle null (no cross-recycle persistence).
        }
        return null;
    }
}
