# Walkthrough - Dynamic Layer Ordering

I have implemented a dynamic Z-index system for Image B, allowing you to move it above or below Image A.

## Key Changes

### 1. Initial State: Bottom Placement
When you select and crop Image B (via **4. 选择并拼合图片 B**), it is now automatically placed at the **bottom** of the layer stack (behind Image A).

### 2. Z-Order Controls (+/-)
I replaced the layer type selection for B with ordering buttons:
- **`+` Button**: Moves Image B **Up** to the top layer (covering Image A).
- **`-` Button**: Moves Image B **Down** to the bottom layer (hidden behind Image A).

### 3. Layer Priority Logic
The composition engine now dynamically swaps the drawing order based on your selection:
```kotlin
if (isB_OnTop) {
    // Draw A, then overlay B
    draw(LayerA)
    draw(LayerB)
} else {
    // Draw B, then overlay A
    draw(LayerB)
    draw(LayerA)
}
```

## Verification Summary

### Automated Build
- `app:assembleDebug` completed successfully.

### Manual Verification Flow
1. Load and crop Image A.
2. Load and crop Image B.
3. Observe that Image B is behind Image A.
4. Click **`+`** in the B control section.
5. Observe that Image B is now on top of Image A.
6. Click **`-`** and observe it moves back to the bottom.
