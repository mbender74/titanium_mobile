# Ti.UI.View Android – Comprehensive Optimization Plan

> **Status:** Planning
> **Scope:** `TiUIView`, `TiViewProxy`, `TiCompositeLayout`, Kroll Proxy-Layer, Widget Subclasses
> **Created:** 2026-06-09
> **Related:** `ListView_OPTIMIZATION_PLAN.md`, `TableView_OPTIMIZATION_PLAN.md`, `optimization_plan.md`

---

## Executive Summary

Die Analyse von `TiUIView` (2414 Zeilen), `TiViewProxy` (1274 Zeilen), `TiCompositeLayout` (1261 Zeilen), `TiBorderWrapperView` (309 Zeilen), `TiGradientDrawable` (346 Zeilen), `TiDrawableReference` (1002 Zeilen), `TiDimension` und allen Widget-Subclasses hat **28 konkrete Optimierungspotenziale** identifiziert, gruppiert in 7 Kategorien:

| Kategorie | Items | Geschätzter Impact |
|-----------|-------|-------------------|
| **A. Layout & Batching** | 4 | Hoch – bis zu 60% weniger Layout-Passes |
| **B. Property & Event Handling** | 4 | Mittel-Hoch – weniger GC-Druck, schnellere Events |
| **C. Rendering & Drawing** | 5 | Hoch – 60-70% weniger Allocationen/Frame |
| **D. Touch & Gestures** | 3 | Mittel – ~90% weniger GC bei Pinch/Rotate |
| **E. Memory & Lifecycle** | 4 | Mittel – weniger Memory Leaks, sauberer Cleanup |
| **F. TiDimension-Allokationen** | 3 | Hoch – 80-90% weniger Layout-Allocationen |
| **G. Widget-spezifisch** | 5 | Mittel – Text, Image, Card Rendering |

**Kernproblem:** Jedes JS-seitige Setzen von Layout-Properties (`left`, `top`, `width`, `height`) löst **unabhängige** `requestLayout()`-Aufrufe aus. Bei 4 Properties in einer JS-Sequenz = 4 vollständige Layout-Passes. iOS hat dies bereits mit einem 50ms-debounced Dirty-Flag-System gelöst.

**Rendering-Kernproblem:** Eine einzige bordered View mit Gradient alloziiert **~1062 Bytes pro Frame** (60fps = ~64 KB/s nur für den Draw-Cycle). Zusätzlich: `invalidate()` wird **immer** am Ende von `processProperties()` aufgerufen, unabhängig von tatsächlichen visuellen Änderungen.

---

## Status der bestehenden Pläne

| Plan | Items | Implementiert? |
|------|-------|----------------|
| `TiView_OPTIMIZATION_PLAN.md` (alt) | 4 | **Nein** – alle noch im Code |
| `ListView_OPTIMIZATION_PLAN.md` | 4 | **Nein** |
| `TableView_OPTIMIZATION_PLAN.md` | 20+ | **Nein** |
| `optimization_plan.md` (Master) | 15 | **Nein** |

---

## Kategorie A: Layout & Batching (Höchste Priorität)

### A1: Layout-Pass Batching mit Dirty Flags

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/view/TiUIView.java`

**Problem:** `propertyChanged()` (Zeilen 593–930) ruft für jedes Layout-Property (`left`, `top`, `right`, `bottom`, `center`, `width`, `height`) unabhängig `layoutNativeView()` auf. Drei JS-Zeilen wie `view.left = 10; view.top = 20; view.width = 100;` lösen 3 vollständige `requestLayout()`-Aufrufe aus.

**iOS-Lösung:** `TiViewProxy.m` verwendet ein `dirtyflags`-Int mit atomaren Bit-Operationen (`OSAtomicTestAndSetBarrier`). Mehrere Property-Änderungen innerhalb eines Runloop-Cycles werden gebündelt. Zusätzlich: 50ms-debounced `TiLayoutQueue` mit `CFRunLoopTimer`.

**Lösung:**
1. `TiUIView` ein `private int layoutDirtyFlags = 0;` hinzufügen
2. `propertyChanged()` setzt nur das Flag statt `layoutNativeView()` aufzurufen
3. Einen `Choreographer.PostFrameCallback` verwenden, der alle 16ms (60fps) alle pending Layout-Changes batched
4. Alternativ: `ViewTreeObserver.OnGlobalLayoutListener` mit Dirty-Flag-Check

```java
// Pseudocode
private static final int DIRTY_LEFT = 0x01;
private static final int DIRTY_TOP = 0x02;
private static final int DIRTY_SIZE = 0x04;
private static final int DIRTY_CENTER = 0x08;
private int layoutDirtyFlags = 0;

private void markLayoutDirty(int flags) {
    layoutDirtyFlags |= flags;
    Choreographer.getInstance().postFrameCallback(layoutBatchCallback);
}

private final Choreographer.FrameCallback layoutBatchCallback = () -> {
    if (layoutDirtyFlags != 0) {
        layoutNativeView();
        layoutDirtyFlags = 0;
    }
};
```

**Geschätzter Impact:** 50–60% weniger Layout-Passes bei Multi-Property-Updates.

---

### A2: Equality Checks in propertyChanged()

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/view/TiUIView.java`, Zeilen 593–930

**Problem:** `propertyChanged()` löst Layout/Invalidate-Logik aus, auch wenn `newValue` identisch zum aktuellen Wert ist.

**Lösung:** Early-exit am Anfang von `propertyChanged()`:

```java
@Override
public void propertyChanged(String key, Object oldValue, Object newValue, KrollProxy proxy)
{
    // Early exit: no change
    if (oldValue != null && oldValue.equals(newValue)) {
        return;
    }
    // ... rest of method
}
```

**Achtung:** Bei `TiDimension`-Werten muss `TiDimension.equals()` korrekt implementiert sein. Für Colors: `TiConvert.toColor()` muss vorher konsistent angewendet werden.

**Geschätzter Impact:** 5–10% Reduktion redundanter Layout-Passes.

---

### A3: Z-Index Sort Optimization (Dirty Flag für resort())

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/view/TiUIView.java`, Zeilen 504–510 (`resort()`)

**Problem:** `resort()` wird häufig aufgerufen, löst einen `TreeSet`-Sort und `requestLayout()` aus – auch wenn sich die Z-Index-Reihenfolge nicht geändert hat.

**Lösung:** Dirty-Flag für Z-Index-Änderungen:

```java
private boolean zIndexChanged = false;

@Override
public void propertyChanged(String key, Object oldValue, Object newValue, KrollProxy proxy)
{
    if (TiC.PROPERTY_Z_INDEX.equals(key)) {
        zIndexChanged = true;
        // ... set property
    }
    // ...
}

private void resort()
{
    if (!zIndexChanged && children.size() <= 1) {
        return; // Nothing changed
    }
    // ... existing sort logic
    zIndexChanged = false;
}
```

**Geschätzter Impact:** 2–5% schnellere Layouts bei häufigen Z-Index-Änderungen.

---

### A4: TiCompositeLayout – Padding Calculation Caching + Double-Measure Fix

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/view/TiCompositeLayout.java`, Zeilen 661+ (`constrainChild()`)

**Problem 1:** `getViewWidthPadding()` und `getViewHeightPadding()` werden pro Child in `onMeasure()` **zweimal** aufgerufen (redundant).

**Problem 2 (kritisch):** In `onLayout()` (Zeilen 1000–1003) werden Views mit Pin-basierter Größensetzung (`left`+`right` oder `top`+`bottom`) **zweimal gemessen**:

```java
// TiCompositeLayout.java Zeilen 1000-1003
if (newWidth != child.getMeasuredWidth() || newHeight != child.getMeasuredHeight()) {
    int newWidthSpec = MeasureSpec.makeMeasureSpec(newWidth, MeasureSpec.EXACTLY);
    int newHeightSpec = MeasureSpec.makeMeasureSpec(newHeight, MeasureSpec.EXACTLY);
    child.measure(newWidthSpec, newHeightSpec);  // SECOND measure pass!
}
```

**Lösung:**
1. Padding-Werte pro Child cachen:

```java
// In TiUIView:
private float cachedWidthPadding = -1;
private float cachedHeightPadding = -1;
private int parentWidthForPadding = -1;

private float getViewWidthPadding() {
    int parentW = getParent() != null ? ((View) getParent()).getWidth() : -1;
    if (cachedWidthPadding < 0 || parentWidthForPadding != parentW) {
        cachedWidthPadding = /* existing calculation */;
        parentWidthForPadding = parentW;
    }
    return cachedWidthPadding;
}
```

2. Pin-basierte Dimensionen bereits in `constrainChild()` berechnen, nicht deferred bis `onLayout()`:

```java
// In constrainChild():
// Pin-basierte Größe VOR child.measure() berechnen, falls parent EXACTLY ist
if (MeasureSpec.getMode(parentWidth) == MeasureSpec.EXACTLY) {
    int pinWidth = calculateWidthFromPins(child);
    if (pinWidth > 0) {
        width = pinWidth;
        widthMode = MeasureSpec.EXACTLY;
    }
}
```

**Geschätzter Impact:** 50% weniger measure() calls für pinned views. 5–10% schnellere onMeasure() bei verschachtelten Layouts.

---

## Kategorie B: Property & Event Handling

### B1: TiUIView.propertyChanged() – String-Vergleichskette optimieren

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/view/TiUIView.java`, Zeilen 593–930

**Problem:** ~40 `if/else if`-Zweige mit `key.equals()` – linearer Scan durch alle Property-Namen. Für nicht-handled Properties werden alle 40 Vergleiche durchgeführt.

**iOS-Vergleich:** iOS nutzt eine `switch`-ähnliche Dispatch-Struktur über die `dirtyflags`-Bits, nicht string-basiert.

**Lösung:** Dispatch-Tabelle mit Property-Name-to-Handler-Mapping:

```java
// Statische Lookup-Tabelle (einmalig bei Class-Load)
private static final Map<String, PropertyHandler> PROPERTY_HANDLERS = new HashMap<>();
static {
    PROPERTY_HANDLERS.put(TiC.PROPERTY_LEFT, (view, old, newVal, proxy) -> ((TiUIView)view).handleLeft(old, newVal, proxy));
    PROPERTY_HANDLERS.put(TiC.PROPERTY_TOP, (view, old, newVal, proxy) -> ((TiUIView)view).handleTop(old, newVal, proxy));
    // ... weitere
}

@FunctionalInterface
private interface PropertyHandler {
    void handle(TiUIView view, Object oldValue, Object newValue, KrollProxy proxy);
}

@Override
public void propertyChanged(String key, Object oldValue, Object newValue, KrollProxy proxy)
{
    PropertyHandler handler = PROPERTY_HANDLERS.get(key);
    if (handler != null) {
        handler.handle(this, oldValue, newValue, proxy);
        return;
    }
    // Fallback: subclass override
    handlePropertyChanged(key, oldValue, newValue, proxy);
}
```

**Geschätzter Impact:** O(1) statt O(40) pro Property-Change. Signifikant für Views mit vielen Property-Änderungen.

---

### B2: processProperties() – invalidate() nur bei tatsächlichen Änderungen

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/view/TiUIView.java`, Zeilen 971–1130 (`processProperties()`)

**Problem:** `processProperties()` ruft **immer** `nativeView.postInvalidate()` am Ende auf (Zeile 893), unabhängig davon, ob sich visuell etwas geändert hat. Dies passiert auch in **allen Widget-Subclasses** (TiUILabel Zeile 507, TiUIButton Zeile 213, TiUISwitch Zeile 171, TiUIActivityIndicator Zeile 90).

**Lösung:** Dirty-Tracking für Background-Subproperties und visuelles invalidate() nur bei Änderungen:

```java
// In processProperties():
boolean visualChanged = false;
// ... während Property-Verarbeitung:
if (changedBackground || changedBorder || changedOpacity) {
    visualChanged = true;
}
if (visualChanged) {
    nativeView.postInvalidate();
}
```

**Geschätzter Impact:** 20-30% weniger invalidate() calls bei häufigen Property-Updates.

---

### B3: TiViewProxy – hierarchyHasListener() Caching

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/proxy/TiViewProxy.java` + `KrollProxy.java` (Zeilen 970–980)

**Problem:** `hierarchyHasListener()` traversiert bei JEDEM `fireEvent()` die gesamte Parent-Hierarchie rekursiv. Bei tiefen Hierarchien (TabGroup → Window → View → ListView → ...) = 7+ Map-Lookups pro Event.

**iOS-Vergleich:** iOS nutzt `dispatch_barrier_sync` auf einem dedicated `listenerQueue` und hat `_hasListeners:type` mit Early-Exit.

**Lösung:** Listener-Count pro Event-Type pro Proxy cachen:

```java
// In KrollProxy:
private Map<String, Integer> listenerCountCache = new ConcurrentHashMap<>();
private long listenerCacheExpiry = 0;
private static final long CACHE_TTL_MS = 100; // 100ms TTL

public boolean hierarchyHasListener(String event)
{
    long now = SystemClock.uptimeMillis();
    if (now - listenerCacheExpiry < CACHE_TTL_MS) {
        Integer count = listenerCountCache.get(event);
        return count != null && count > 0;
    }

    // Cache miss: recompute
    boolean has = computeAndCacheListenerCount(event);
    listenerCacheExpiry = now;
    return has;
}
```

---

### B4: TiViewProxy – getRect()/getSize() TiDimension-Allokationen reduzieren

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/proxy/TiViewProxy.java`, Zeilen 236–280

**Problem:** `getRect()` und `getSize()` alloziieren pro Aufruf 6+ `TiDimension`-Objekte. Diese Methoden werden oft aus JS aufgerufen (Debug, Layout-Berechnungen).

**Lösung:** Primitive double-Werte statt TiDimension-Objekten zurückgeben:

```java
public KrollDict getRect()
{
    View v = getView();
    if (v == null) {
        return new KrollDict();
    }
    // Return raw doubles, avoid TiDimension allocation
    KrollDict rect = new KrollDict();
    rect.put(TiC.PROPERTY_LEFT, (double) v.getLeft());
    rect.put(TiC.PROPERTY_TOP, (double) v.getTop());
    rect.put(TiC.PROPERTY_WIDTH, (double) v.getWidth());
    rect.put(TiC.PROPERTY_HEIGHT, (double) v.getHeight());
    return rect;
}
```

---

## Kategorie C: Rendering & Drawing (Neu – vertieft analysiert)

### C1: TiBorderWrapperView – Path/RectF Pooling

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/view/TiBorderWrapperView.java`, Zeilen 80–125 (`onDraw()`)

**Problem:** `onDraw()` erstellt pro Frame **4–5 Objekte**:
- `RectF innerRect` (24 bytes)
- `RectF outerRect` (24 bytes)
- `Path outerPath` (~100 bytes)
- `float[] innerRadius` (64 bytes, 8 floats)
- `Path innerPath` (~100 bytes)

**Gesamt: ~312 bytes/Frame** für eine View mit Border + Radius. Bei 60fps = **~19 KB/s pro View**.

**Lösung:** Pre-allocation als Member-Fields, Wiederverwendung über draws:

```java
// In init/constructor:
this.innerRect = new RectF();
this.outerRect = new RectF();
this.outerPath = new Path();
this.innerPath = new Path();
this.innerRadius = new float[8];

// In onDraw():
this.innerRect.set(bounds.left + padding, bounds.top + padding, bounds.right - padding, bounds.bottom - padding);
this.outerRect.set(bounds);
this.outerPath.reset();
// ... reuse outerPath, innerPath, innerRadius
```

**Geschätzter Impact:** Eliminierung von ~312 Bytes/Frame/View mit Border. Bei 10 bordered Views = ~190 KB/s eingespart.

---

### C2: TiGradientDrawable – Shader Recreation Caching

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/view/TiGradientDrawable.java`

**Problem:** `resize()` (Zeilen ~340+) erstellt bei **jeder** Bounds-Änderung einen neuen `Shader` (LinearGradient/RadialGradient). Pro resize:
- `LinearGradient` oder `RadialGradient` (~100 bytes)
- 4 `getAsPixels()` calls (jeweils Float-Boxing)

**Zusätzlich:** Der Konstruktor alloziiert für Radial-Gradients mit 3 Farben:
- `TiPoint` x2 (~40 bytes)
- `TiDimension` x2 (~40 bytes)
- `int[]` original + backfill (~44 bytes)
- `float[]` backfill (~40 bytes)
- `GradientShaderFactory` (~50 bytes)
- **Total: ~214 bytes pro Konstruktion**

**Lösung:** Shader cachen und nur bei tatsächlichen Änderungen neu erstellen:

```java
private Shader cachedShader;
private int cachedWidth;
private int cachedHeight;
private int cachedColorsHash;

public Shader resize(int width, int height)
{
    int colorsHash = Arrays.hashCode(colors);
    if (cachedShader != null && cachedWidth == width && cachedHeight == height && cachedColorsHash == colorsHash) {
        return cachedShader; // Reuse
    }
    // Create new
    Shader shader = createShader(width, height);
    cachedShader = shader;
    cachedWidth = width;
    cachedHeight = height;
    cachedColorsHash = colorsHash;
    return shader;
}
```

**Geschätzter Impact:** ~150 Bytes/Frame für Gradient-Views bei Resize-Ereignissen.

---

### C3: invalidate() Only-on-Change Pattern (global)

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/view/TiUIView.java` + alle Widget-Subclasses

**Problem:** `invalidate()` wird **immer** am Ende von `processProperties()` aufgerufen:
- `TiUIView.java` Zeile 893
- `TiUILabel.java` Zeile 507
- `TiUIButton.java` Zeile 213
- `TiUISwitch.java` Zeile 171
- `TiUIActivityIndicator.java` Zeile 90

Das bedeutet: **Jede** Property-Änderung (auch `opacity`, `touchEnabled`, `backgroundColor` mit gleichem Wert) löst ein vollständiges Neuzeichnen aus.

**Lösung:** Dirty-Flag für visuelle Änderungen, invalidate() nur wenn visuell etwas geändert wurde:

```java
private boolean visualDirty = false;

// In propertyChanged():
if (affectsVisualAppearance(key)) {
    visualDirty = true;
}

// In processProperties() am Ende:
if (visualDirty) {
    nativeView.postInvalidate();
    visualDirty = false;
}
```

**Geschätzter Impact:** 20-30% weniger invalidate() calls bei häufigen Property-Updates.

---

### C4: disableHWAcceleration – Bedingung optimieren

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/view/TiUIView.java`, Zeilen 2209–2217

**Problem:** `disableHWAcceleration()` schaltet die `TiBorderWrapperView` auf `LAYER_TYPE_SOFTWARE`, wenn:
1. Eine Border vorhanden IST UND
2. Die Hintergrundfarbe Alpha < 255 hat (semi-transparent)

Das betrifft eine **sehr häufige Kombination** (Border + leicht transparenter Hintergrund). Software-Rendering ist deutlich langsamer für Animationen und Scrolling.

**Lösung:** 
1. `keepHardwareMode` Property evaluieren (wird gelesen, aber nicht korrekt angewendet)
2. Optional: Nur bei tatsächlicher Transparenz der Border deaktivieren, nicht bei der Hintergrundfarbe

```java
protected void disableHWAcceleration()
{
    boolean hasBorder = hasBorder();
    boolean bgHasAlpha = hasBgColor() && getBgAlpha() < 255;
    boolean keepHW = proxy.hasProperty("keepHardwareMode") && TiConvert.toBoolean(proxy.getProperty("keepHardwareMode"), false);
    
    if (hasBorder && bgHasAlpha && !keepHW) {
        this.borderView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    } else if (keepHW) {
        this.borderView.setLayerType(View.LAYER_TYPE_NONE, null);
    }
}
```

**Geschätzter Impact:** Bessere Animation-Performance bei bordered Views mit semi-transparentem Hintergrund.

---

### C5: TiCompositeLayout – onMeasure/OnLayout Complexity Reduction

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/view/TiCompositeLayout.java`

**Problem:** 
- `onMeasure()` (Zeilen 431–530): O(N) pro Child, aber hoher konstanter Faktor durch `constrainChild()`
- `onLayout()` (Zeilen 920–1040): O(N log N) bei Z-Index-Sort (TreeSet), O(N) sonst
- **Pro Child in onMeasure():** 2–9 `asPixels()` calls
- **Pro Child in onLayout():** 4–8 zusätzliche `asPixels()` calls
- **Total: 6n bis 17n asPixels() calls pro Layout-Pass** (n = Children)

**Lösung:**
1. `constrainChild()` reduziert auf 2 `asPixels()` calls für Standard-Cases (width + height)
2. Pin-basierte Dimensionen in `constrainChild()` berechnen (vermeidet Double-Measure)
3. TreeSet-Sortierung nur bei Bedarf (A3)

**Geschätzter Impact:** 30-40% schnellere Layout-Passes bei vielen Children.

---

## Kategorie D: Touch & Gestures

### D1: KrollDict Pooling in Touch-Event-Handlern

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/view/TiUIView.java`, Zeilen 1815–1980

**Problem:** `ScaleGestureDetector`-Listener alloziiert pro `onScale()`-Call einen neuen `KrollDict`. Bei 60fps Pinch-Gesture = ~120 KrollDicts/Geste. Analog: `doRotationEvent()` alloziiert pro `ACTION_MOVE` mit 2+ Fingern.

**iOS-Vergleich:** iOS nutzt lazy gesture recognizer creation und Early-Exit wenn kein Listener.

**Lösung:** Object Pool für KrollDict in Touch-Events:

```java
// Simple pool (or use existing object pool infrastructure)
private final Queue<KrollDict> eventDictPool = new ConcurrentLinkedQueue<>();

private KrollDict borrowEventDict() {
    KrollDict dict = eventDictPool.poll();
    return dict != null ? dict : new KrollDict();
}

private void returnEventDict(KrollDict dict) {
    dict.clear();
    eventDictPool.add(dict);
}
```

**Geschätzter Impact:** ~90% weniger GC-Druck während Pinch/Rotate-Gesten.

---

### D2: Touch-Event-Gate (handlesTouches-Pattern)

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/view/TiUIView.java`, Zeilen 1815–1980

**Problem:** Touch-Event-Verarbeitung (GestureDetector, ScaleGestureDetector, anonymous listeners) wird immer initialisiert, auch wenn kein JS-Listener registriert ist.

**iOS-Vergleich:** iOS hat `updateTouchHandling()` das `handlesTouches` basierend auf Listener-Präsenz setzt.

**Lösung:** Touch-Handling nur aktivieren wenn Listener existieren:

```java
private boolean touchListenersActive = false;

private void updateTouchHandling() {
    boolean hasListeners = proxy != null && proxy.hasListeners("touchstart");
    if (hasListeners != touchListenersActive) {
        touchListenersActive = hasListeners;
        if (hasListeners) {
            registerTouchEvents();
        } else {
            unregisterTouchEvents();
        }
    }
}
```

---

### D3: Lazy Gesture Detector Creation

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/view/TiUIView.java`

**Problem:** `GestureDetector` und `ScaleGestureDetector` werden in `registerTouchEvents()` (Zeile 1818) immer neu erstellt, auch wenn sie bereits existieren.

**Lösung:** Lazy Initialization mit null-Check:

```java
private GestureDetector gestureDetector;
private ScaleGestureDetector scaleGestureDetector;

private void registerTouchEvents() {
    if (gestureDetector == null) {
        gestureDetector = new GestureDetector(context, ...);
    }
    if (scaleGestureDetector == null) {
        scaleGestureDetector = new ScaleGestureDetector(context, ...);
    }
    // ... configure existing detectors
}
```

---

## Kategorie E: Memory & Lifecycle

### E1: OnGlobalLayoutListener Leak Prevention

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/view/TiUIView.java`, Zeilen 378–427

**Problem:** `OnGlobalLayoutListener` und `OnPreDrawListener` werden in einem `try-catch` entfernt. Wenn `removeOnGlobalLayoutListener()` `IllegalStateException` wirft (bekannter Android-Bug), bleibt der Listener hängen.

**Lösung:** Listener immer in `finally` entfernen:

```java
try {
    // animation logic
} catch (IllegalStateException e) {
    Log.w(TAG, "Animation state error", e);
} finally {
    // ALWAYS remove listeners
    view.getViewTreeObserver().removeOnGlobalLayoutListener(globalLayoutListener);
    view.getViewTreeObserver().removeOnPreDrawListener(preDrawListener);
}
```

---

### E2: sRunningViews Cleanup in TiAnimationBuilder

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/util/TiAnimationBuilder.java`, Zeile 88

**Problem:** `sRunningViews` (statische `ArrayList<WeakReference<View>>`) wächst über die Zeit, da stale References nie bereinigt werden.

**Lösung:** Periodic cleanup + Animation-End-Callback:

```java
private static final ScheduledExecutorService cleanupExecutor =
    Executors.newSingleThreadScheduledExecutor();

static {
    cleanupExecutor.scheduleAtFixedRate(() -> {
        synchronized (sRunningViews) {
            sRunningViews.removeIf(ref -> ref.get() == null);
        }
    }, 5, 5, TimeUnit.SECONDS);
}

// Plus: cleanup in AnimatorListener.onAnimationEnd()
```

---

### E3: ScaleGestureDetector Cleanup in release()

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/view/TiUIView.java`, Zeilen 1342–1400 (`release()`)

**Problem:** `ScaleGestureDetector` wird in `registerTouchEvents()` als lokale Variable alloziiert (Zeile 1818) aber in `release()` nicht explizit freigegeben (nur `detector = null` für `GestureDetector`).

**Lösung:** `ScaleGestureDetector` als Member-Field und in `release()` nullen:

```java
private ScaleGestureDetector scaleDetector;

@Override
public void release()
{
    // ... existing cleanup
    if (scaleDetector != null) {
        scaleDetector = null;
    }
    // ...
}
```

---

### E4: TiViewProxy – styleSheetUrlCache LRU

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/proxy/TiViewProxy.java`, Zeile 149

**Problem:** `styleSheetUrlCache` ist ein statischer `HashMap` ohne Größenlimit. Bei dynamischen Views kann dies Memory Leaks verursachen.

**Lösung:** LRU-Cache mit begrenzter Größe:

```java
// Replace:
// private static final HashMap<TiUrl, String> styleSheetUrlCache = new HashMap<>(5);

// With:
private static final int STYLE_SHEET_CACHE_SIZE = 20;
private static final Map<TiUrl, String> styleSheetUrlCache =
    Collections.synchronizedMap(new LinkedHashMap<TiUrl, String>(STYLE_SHEET_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<TiUrl, String> eldest) {
            return size() > STYLE_SHEET_CACHE_SIZE;
        }
    });
```

---

## Kategorie F: TiDimension-Allokationen (Neu – vertieft analysiert)

### F1: TiDimension Parsing – Regex-Overhead eliminieren

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/TiDimension.java`

**Problem:** Der `TiDimension(String, int)` Konstruktor (Zeilen 97–142) führt bei **jedem Aufruf** einen vollständigen Regex-Parse durch:
1. `DIMENSION_PATTERN.matcher(svalue.trim()).matches()` – erstellt neuen `Matcher`
2. `Float.parseFloat(m.group(1))` – Float-Boxing
3. Mehrere `String.equals()`-Vergleiche für Unit-Konstanten

**Außerdem:** `TiConvert.toTiDimension()` (in `TiConvert.java`) ruft **immer** `new TiDimension(...)` auf, ohne Caching. Selbst derselbe String `"50dp"` wird 100x geparst.

**Lösung:** String-Interning + Caching:

```java
// In TiDimension:
private static final Map<String, TiDimension> stringCache = new ConcurrentHashMap<>(64);

public TiDimension(String svalue, int valueType) {
    String cacheKey = svalue.trim().toLowerCase() + "|" + valueType;
    TiDimension cached = stringCache.get(cacheKey);
    if (cached != null) {
        // Reuse cached value (TiDimension is effectively immutable after creation)
        this.value = cached.value;
        this.unitType = cached.unitType;
        this.valueType = valueType;
        return;
    }
    // ... existing parsing logic
    stringCache.put(cacheKey, this);
}
```

**Geschätzter Impact:** 80-90% weniger Regex-Parse-Operationen für wiederkehrende Dimension-Strings.

---

### F2: TiDimension-Objekte in LayoutParams cachen

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/view/TiCompositeLayout.java` + `TiUIView.java`

**Problem:** Pro Child in `constrainChild()` werden 2–9 `asPixels()` calls ausgeführt. Jeder Aufruf ist zwar allocation-frei (nach erstem `DisplayMetrics`-Load), aber die **redundante Berechnung** ist kostspielig.

**Lösung:** Pixel-Werte in `LayoutParams` cachen und nur bei Änderung neu berechnen:

```java
// In TiCompositeLayout.LayoutParams:
private float cachedWidthPixels = Float.NaN;
private float cachedHeightPixels = Float.NaN;
private int lastParentWidth = -1;
private int lastParentHeight = -1;

public float getWidthPixels(View parent) {
    int pw = parent != null ? parent.getWidth() : -1;
    int ph = parent != null ? parent.getHeight() : -1;
    if (Float.isNaN(cachedWidthPixels) || lastParentWidth != pw || lastParentHeight != ph) {
        cachedWidthPixels = optionWidth != null ? optionWidth.getAsPixels(parent) : 0;
        lastParentWidth = pw;
        lastParentHeight = ph;
    }
    return cachedWidthPixels;
}
```

Cache invalidieren bei Property-Change.

**Geschätzter Impact:** 60-70% weniger asPixels() calls pro Layout-Pass.

---

### F3: Animation TiDimension-Allokationen reduzieren

**Datei:** `android/titanium/src/java/org/appcelerator/titanium/util/TiAnimationBuilder.java`

**Problem:** `animate()` alloziiert **14 TiDimension-Objekte** allein für die 8 Animations-Properties (top, bottom, left, right, centerX, centerY, width, height). Pro Animation.

**Lösung:** TiDimension-Objekte wiederverwenden oder primitive double-Werte in Animation-Config speichern:

```java
// In TiAnimationBuilder:
// Statt: new TiDimension(value, TYPE_LEFT)
// Speichern: double rawValue + int type
private double animLeft;
private double animTop;
private int animLeftType;
private int animTopType;

// Bei Bedarf: lazy TiDimension creation
private TiDimension getLeftDimension() {
    if (animLeftDimension == null) {
        animLeftDimension = new TiDimension(animLeft, animLeftType);
    }
    return animLeftDimension;
}
```

**Geschätzter Impact:** 14 TiDimension-Allocationen pro Animation eliminiert. Bei parallelen Animationen signifikant.

---

## Kategorie G: Widget-spezifische Optimierungen (Neu – vertieft analysiert)

### G1: TiUILabel – Text-Messung cachen

**Datei:** `android/modules/ui/src/java/ti/modules/titanium/ui/widget/TiUILabel.java`

**Problem:**
- `adjustTextFontSize()` (Zeile 226–242) führt bei jedem Layout-Durchlauf `TextPaint.measureText(text)` in einer `while`-Schleife durch – keine Caching der gemessenen Breiten
- `Html.fromHtml()` (Zeile 476) wird bei jedem HTML-Update aufgerufen, auch bei identischem HTML
- `SpannableStringBuilder` wird bei jedem `updateLabelText()` neu erstellt

**Lösung:** Text-Breiten-Caching:

```java
// In TiUILabel:
private float cachedTextWidth = -1;
private String lastMeasuredText;
private Spanned cachedSpanned;
private String lastHtml;

// In adjustTextFontSize():
if (lastMeasuredText != null && lastMeasuredText.equals(currentText)) {
    return cachedTextWidth; // Use cached
}
// ... measure text
cachedTextWidth = measuredWidth;
lastMeasuredText = currentText;

// In propertyChanged() für HTML:
if (newHtml != null && newHtml.equals(lastHtml)) {
    return; // Skip redundant Html.fromHtml()
}
lastHtml = newHtml;
```

**Geschätzter Impact:** 30-50% schnellere Text-Updates bei häufigen Label-Änderungen.

---

### G2: TiImageView – Bitmap-Referenz cachen

**Datei:** `android/modules/ui/src/java/ti/modules/titanium/ui/widget/TiImageView.java`

**Problem:**
- `setImageBitmap()` (Zeile 157–169) erstellt bei jedem Aufruf ein neues `BitmapDrawable` oder `RippleDrawable`
- `computeBaseMatrix()` (Zeile 175–244) berechnet bei jedem `onLayout()` die Scaling-Matrix neu
- ZoomHandler `ValueAnimator` (Zeile 567–596) ruft `requestLayout()` statt `invalidate()` auf – zu teuer für Matrix-Änderungen

**Lösung:** Bitmap-Referenz cachen und `requestLayout()` durch `invalidate()` ersetzen:

```java
// In TiImageView:
private Bitmap cachedBitmap;

@Override
public void setImageBitmap(Bitmap bitmap) {
    if (bitmap == cachedBitmap) {
        return; // Same bitmap, skip
    }
    cachedBitmap = bitmap;
    // ... set bitmap
}

// In ZoomHandler onAnimationUpdate:
// Statt: tiImageView.requestLayout()
tiImageView.invalidate(); // Matrix change only needs redraw, not relayout
```

**Geschätzter Impact:** 40-50% weniger Layout-Passes während Zoom-Animationen.

---

### G3: TiUIButton – Drawable-Caching

**Datei:** `android/modules/ui/src/java/ti/modules/titanium/ui/widget/TiUIButton.java`

**Problem:** `updateButtonImage()` (Zeile 289–329) ruft bei jeder Änderung `TiUIHelper.getResourceDrawable()` auf, was das Drawable neu laden kann.

**Lösung:** Drawable-Referenz cachen:

```java
// In TiUIButton:
private Drawable cachedButtonDrawable;
private String lastImageUrl;

private void updateButtonImage() {
    if (lastImageUrl != null && lastImageUrl.equals(currentImageUrl) && cachedButtonDrawable != null) {
        return; // Skip redundant drawable load
    }
    lastImageUrl = currentImageUrl;
    // ... load drawable
}
```

---

### G4: TiUISwitch – ColorStateList Caching

**Datei:** `android/modules/ui/src/java/ti/modules/titanium/ui/widget/TiUISwitch.java`

**Problem:** Bei `THUMB_COLOR`/`TINT_COLOR`-Änderungen (Zeile 66–102) wird bei jedem Aufruf eine neue `ColorStateList` erstellt, auch wenn sich die Farben nicht geändert haben.

**Lösung:** Farben cachen und `ColorStateList` nur bei Änderung erstellen:

```java
// In TiUISwitch:
private int cachedThumbColor = -1;
private int cachedTintColor = -1;
private ColorStateList cachedThumbColorStateList;
private ColorStateList cachedTintColorStateList;

private ColorStateList getColorStateList(int color) {
    if (cachedThumbColor == color && cachedThumbColorStateList != null) {
        return cachedThumbColorStateList;
    }
    cachedThumbColor = color;
    cachedThumbColorStateList = /* create */;
    return cachedThumbColorStateList;
}
```

---

### G5: TiUICardView – ShapeAppearanceModel Caching

**Datei:** `android/modules/ui/src/java/ti/modules/titanium/ui/widget/TiUICardView.java`

**Problem:** `setRadius()` (Zeile 281–334) erstellt bei Array-basiertem `borderRadius` ein neues `ShapeAppearanceModel` via Builder-Pattern bei jedem Aufruf.

**Lösung:** `ShapeAppearanceModel` cachen:

```java
// In TiUICardView:
private ShapeAppearanceModel cachedShapeModel;
private float[] lastBorderRadius;

private ShapeAppearanceModel getOrCreateShapeModel(float[] radius) {
    if (cachedShapeModel != null && arraysEqual(lastBorderRadius, radius)) {
        return cachedShapeModel;
    }
    cachedShapeModel = /* build new */;
    lastBorderRadius = radius.clone();
    return cachedShapeModel;
}
```

---

## Implementierungsphasen

### Phase 1: Quick Wins (niedriger Aufwand, hoher Impact)

| # | Optimierung | Datei | Aufwand | Impact |
|---|------------|-------|---------|--------|
| A2 | Equality Checks in propertyChanged | TiUIView.java | 1h | Mittel |
| C1 | Path/RectF Pooling | TiBorderWrapperView.java | 30min | Hoch |
| C3 | invalidate() only-on-change (TiUIView-Basis) | TiUIView.java | 1h | Hoch |
| D2 | Touch-Event-Gate | TiUIView.java | 2h | Hoch |
| E1 | OnGlobalLayoutListener finally | TiUIView.java | 30min | Niedrig |
| E4 | LRU styleSheetUrlCache | TiViewProxy.java | 1h | Mittel |
| F1 | TiDimension String-Caching | TiDimension.java | 2h | Hoch |

**Gesamtaufwand:** ~8 Stunden
**Erwarteter Impact:** 20-30% Verbesserung bei Multi-Property-Updates + Rendering

---

### Phase 2: Core Optimizations (mittlerer Aufwand)

| # | Optimierung | Datei | Aufwand | Impact |
|---|------------|-------|---------|--------|
| A1 | Layout Batching (Choreographer) | TiUIView.java | 1 Tag | Hoch |
| A4 | Padding Cache + Double-Measure Fix | TiCompositeLayout.java | 4h | Hoch |
| B1 | Property Handler Dispatch Map | TiUIView.java | 3h | Mittel |
| B3 | hierarchyHasListener Caching | KrollProxy.java | 2h | Mittel |
| B4 | getRect primitive return | TiViewProxy.java | 1h | Niedrig |
| C2 | TiGradientDrawable Shader Cache | TiGradientDrawable.java | 3h | Mittel |
| D1 | KrollDict Pooling | TiUIView.java | 3h | Hoch |
| D3 | Lazy Gesture Detectors | TiUIView.java | 1h | Mittel |
| E2 | sRunningViews Cleanup | TiAnimationBuilder.java | 2h | Niedrig |
| E3 | ScaleGestureDetector release | TiUIView.java | 30min | Niedrig |
| F2 | LayoutParams Pixel Caching | TiCompositeLayout.java | 3h | Hoch |
| F3 | Animation TiDimension Reduktion | TiAnimationBuilder.java | 2h | Mittel |

**Gesamtaufwand:** ~3.5 Tage
**Erwarteter Impact:** 40-55% Verbesserung bei komplexen UIs

---

### Phase 3: Advanced Optimizations (geringerer Impact, spezifische Fälle)

| # | Optimierung | Datei | Aufwand | Impact |
|---|------------|-------|---------|--------|
| A3 | Z-Index Sort Dirty Flag | TiUIView.java | 2h | Niedrig |
| C4 | disableHWAcceleration optimieren | TiUIView.java | 2h | Mittel |
| B2 | processProperties() visualChanged (Widget-Subclasses) | TiUIView.java + Widgets | 2h | Mittel |

**Gesamtaufwand:** ~1 Tag
**Erwarteter Impact:** 10-15% Verbesserung bei spezialisierten Fällen

---

### Phase 4: Widget-Optimierungen (deferred – später durchführen)

> **Hinweis:** Diese Phase wird zeitversetzt zu den Core-Optimierungen (Phase 1–3) umgesetzt, nachdem die Basis-Performance stabil ist. Die Widget-Optimierungen sind spezifisch und bauen auf den Foundation-Optimierungen auf.

| # | Optimierung | Datei | Aufwand | Impact |
|---|------------|-------|---------|--------|
| G1 | TiUILabel Text-Messung Cache | TiUILabel.java | 3h | Mittel |
| G2 | TiImageView Bitmap Cache + Zoom Fix | TiImageView.java | 3h | Hoch |
| G3 | TiUIButton Drawable Cache | TiUIButton.java | 1h | Niedrig |
| G4 | TiUISwitch ColorStateList Cache | TiUISwitch.java | 1h | Niedrig |
| G5 | TiUIActivityIndicator ContextThemeWrapper Cache | TiUIActivityIndicator.java | 1h | Niedrig |
| G6 | TiUICardView ShapeAppearanceModel Cache | TiUICardView.java | 2h | Niedrig |

**Gesamtaufwand:** ~1 Tag
**Erwarteter Impact:** 15-25% Verbesserung bei Widget-spezifischen Szenarien

**Voraussetzungen vor Phase 4:**
- Phase 1–3 abgeschlossen (Foundation-Optimierungen stabil)
- Baseline-Benchmarks aus Phase 1–3 verglichen
- Widget-Tests laufen durch

---

## Rendering Performance – Detaillierte Analyse

### Per-Frame Allocation Budget (bordered view with gradient, 3 children)

| Komponente | Allocationen | Bytes (geschätzt) |
|------------|-------------|-------------------|
| `TiBorderWrapperView.onDraw()` | 4–5 Objekte | 312 Bytes |
| `TiGradientDrawable.resize()` | 1 Shader + 4 getAsPixels | ~150 Bytes |
| `TiCompositeLayout.onMeasure()` | 8+ TiDimension calls | ~400 Bytes |
| `TiCompositeLayout.onLayout()` | 4+ getAsPixels calls | ~200 Bytes |
| **Total pro Frame** | **~17–20 Objekte** | **~1062 Bytes** |

Bei 60fps: **~64 KB/s Allocation** nur aus dem Draw-Cycle einer einzigen bordered View mit Gradient.

### Big-O Komplexitätsübersicht

| Operation | Komplexität | Anmerkungen |
|-----------|-------------|-------------|
| `TiCompositeLayout.onMeasure()` | O(N) | Linear in Children, hoher konstanter Faktor |
| `TiCompositeLayout.onLayout()` | O(N log N) | TreeSet-Sort bei zIndex-Änderung |
| `TiCompositeLayout.onLayout()` (kein Sort) | O(N) | Linear, aber Double-Measure für pinned views |
| `TiBorderWrapperView.onDraw()` | O(1) | Feste Anzahl Canvas-Operationen |
| `TiGradientDrawable.resize()` | O(1) | Single shader creation |
| `TiGradientDrawable` Konstruktor | O(C) | C = Anzahl Farben (Array-Expansion) |
| `TiDrawableReference.getBitmap()` | O(1) amortisiert | Mit TiImageCache hit |
| `TiDrawableReference.getBitmap()` | O(I) | Ohne Cache (I = InputStream + Decode) |
| `TiUIView.propertyChanged()` | O(P) | P = Anzahl Properties (copy + iterate) |
| `TiDimension.toString()` Konstruktor | O(1) | Regex-Parse, allocation-frei nach Cache-Warmup |
| `TiDimension.asPixels()` | O(1) | Arithmetik, allocation-frei nach DisplayMetrics-Load |

### Kritischer Pfad: JavaScript Property Change → Screen Update

```
JS: view.borderColor = "red"
  → Kroll proxy fires propertyChanged("borderColor", ...)
    → TiUIView.propertyChanged()
      → proxy.getProperties()          [HashMap alloc]
      → hasBorder(d)                   [key iteration]
      → handleBorderProperty()
        → borderView.setColor()
        → borderView.postInvalidate()  [message queue]
      → nativeView.postInvalidate()    [message queue]
  → Main thread processes postInvalidate
    → TiBorderWrapperView.onDraw()     [4-5 allocations]
      → canvas.drawPath() x2
      → canvas.clipPath()
```

### invalidate() Call-Sites (dominant)

| Location | Datei | Zeile | Kontext |
|----------|-------|-------|---------|
| **DOMINANT** | `TiUIView.java` | 893 | `propertyChanged()` – jeder Background/Border/Opacity Change |
| `TiUIView.java` | 1560–1561 | `initializeBorder()` | Border Initialization |
| `TiUIView.java` | 1590 | `handleBorderProperty()` | Border Property Runtime Change |
| `TiUIView.java` | 2057 | `setAlpha()` | Alpha/Opacity Change |
| `TiUILabel.java` | 507 | `processProperties()` | Label Text/Color Change |
| `TiUIButton.java` | 213 | `processProperties()` | Button State Change |
| `TiUISwitch.java` | 171 | `updateButton()` | Switch Toggle |
| `TiUIActivityIndicator.java` | 90 | `processProperties()` | Spinner Animation |

**Befund:** `TiUIView.propertyChanged()` Zeile 893 ist die **dominante Invalidation-Quelle** – feuert bei jeder einzelnen Property-Änderung, die das visuelle Erscheinungsbild berührt, unabhängig davon ob die visuelle Änderung tatsächlich stattgefunden hat.

---

## iOS vs. Android – Pattern Comparison

| Pattern | iOS | Android | Adoption Priority |
|---------|-----|---------|-------------------|
| **Debounced Layout Batching** | 50ms CFRunLoopTimer + TiLayoutQueue | None | **Hoch** (A1) |
| **Dirty Flags** | `int dirtyflags` mit atomaren Bit-Ops | None | **Hoch** (A1, A3) |
| **Listener Count Gate** | `_hasListeners:type` Early-Exit | Always traverse hierarchy | **Mittel** (B3, D2) |
| **Lazy Gesture Recognizers** | Singleton getter pattern | Always recreate | **Mittel** (D3) |
| **Object Pooling** | dispatch_once caches | None | **Hoch** (C1, D1) |
| **LRU Caches** | Various static caches | Unbounded HashMap | **Mittel** (E4) |
| **Position/Size Cache** | `positionCache`, `sizeCache` | Recalculated every time | **Mittel** (F2) |
| **invalidate() only-on-change** | Implicit via dirty flags | Always invalidates | **Hoch** (C3) |
| **Shader/Drawable Caching** | Implicit via state management | Recreated every time | **Mittel** (C2, G3) |
| **Text Measurement Cache** | Implicit via CoreText caching | No cache | **Mittel** (G1) |

---

## Verification

### Unit Tests
1. Layout-Batching: Test dass 4 Property-Changes = 1 `requestLayout()`-Call
2. Equality Check: Test dass `view.left = 10; view.left = 10;` = 0 Layout-Passes
3. Touch-Gate: Test dass ohne Listener = kein GestureDetector erstellt
4. Object Pooling: Test dass KrollDicts korrekt zurückgegeben und wiederverwendet werden
5. TiDimension Caching: Test dass gleicher String = gleicher TiDimension (Cache-Hit)
6. Double-Measure Fix: Test dass pinned view nur einmal gemessen wird

### Integration Tests
1. **Komplexes Layout:** Nested TiCompositeLayout mit 50+ Children, Property-Updates messen
2. **Pinch-Gesture:** 60fps Pinch/Rotate auf 10 Sekunden – GC-Allokationen messen
3. **Animation:** 10 parallele Animationen – Frame-Drops zählen
4. **Memory:** 1000 Views erstellen/zerstören – Memory-Leak prüfen (MAT/Profiler)
5. **Bordered View mit Gradient:** 60fps Rendering – Allocation Rate messen
6. **Text-Update:** 100 Label-Updates/sekunde – measureText() Aufrufe zählen

### Build & Regression
1. `npm run build:android` – keine Kompilierfehler
2. `npm run test:android` – alle Integrationstests bestehen
3. `npm run lint:android` – Java-Style korrekt
4. Manuelle Tests: Alle Ti.UI.View-Subwidgets (Button, Label, ImageView, WebView, etc.)

---

## Metrics & Benchmarking

Vor der Implementierung Baseline messen:

| Metric | Tool | Baseline | Target |
|--------|------|----------|--------|
| Layout-Passes bei 4 Property-Changes | Android Profiler | 4 | 1 |
| GC-Allokationen pro Pinch-Geste (10s) | Android Profiler | ~120 KrollDicts | <20 |
| `onMeasure` duration (50-Child Layout) | Android Profiler | X ms | <0.6x |
| `hierarchyHasListener` duration (7-level deep) | JUnit Benchmark | Y ms | <0.3x |
| Memory footprint (1000 Views) | MAT Leak Canary | Z MB | <0.8x |
| **Allocation Rate (bordered view, 60fps)** | **Android Profiler** | **~64 KB/s** | **<15 KB/s** |
| **invalidate() calls pro Property-Change** | **Android Profiler** | **1 (immer)** | **0.3 (nur bei Änderung)** |
| **asPixels() calls pro Child pro Layout** | **Custom Benchmark** | **6–17** | **2–4** |
| **TiDimension-Allokationen pro Animation** | **Custom Benchmark** | **14** | **0–2** |
| **Text measureText() calls pro Label-Update** | **Custom Benchmark** | **3–5** | **0–1** |

---

## Offene Fragen

1. **Choreographer vs. Handler:** Sollte Layout-Batching auf `Choreographer` (vsync-gated) oder `Handler` (time-based) basieren? Choreographer ist performanter, Handler ist einfacher zu testen.
2. **Backward Compatibility:** Sollen die Optimierungen standardmäßig aktiv sein oder über eine Build-Flag (`ti.layoutBatchingEnabled`) steuerbar?
3. **TiDimension.equals():** Ist die Gleichheitsprüfung in `TiDimension` korrekt implementiert für alle Typen (TYPE_DIP, TYPE_PERCENT, TYPE_PIXEL)?
4. **Thread-Safety:** Die Property Handler Dispatch Map muss thread-sicher sein (static final = immutable, also safe).
5. **Subclass-Override:** Wie beeinflussen die Optimierungen Subclasses wie `TiUILabel`, `TiUIButton`? Muss `handlePropertyChanged()` in Subclasses angepasst werden?
6. **TiImageCache SoftReference vs. LRU:** Sollte `TiImageCache` von `SoftReference` auf `LruCache` umgestellt werden? SoftReference ist unvorhersehbar unter Memory-Pressure.
7. **Double-Measure Tradeoff:** Vermeidet die Double-Measure-Optimierung korrekte wrap_content-Berechnungen? Muss `hasAutoSizedWidth/Height` angepasst werden?
8. **Shader Cache Memory:** Der Shader-Cache in `TiGradientDrawable` muss invalidiert werden, wenn sich die Drawable-Bounds ändern. Wie granular soll der Cache sein?
