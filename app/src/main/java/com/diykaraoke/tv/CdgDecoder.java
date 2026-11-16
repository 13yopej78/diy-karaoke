package com.diykaraoke.tv;

/**
 * Decodes CD+G (karaoke graphics) packets into a 300x216 ARGB pixel buffer.
 * Pure Java so it can be tested off-device.
 */
public class CdgDecoder {
    public static final int WIDTH = 300;
    public static final int HEIGHT = 216;
    /** CD+G streams run at 300 packets per second. */
    public static final int PACKETS_PER_SECOND = 300;
    private static final int PACKET_SIZE = 24;

    private static final int CMD_MASK = 0x3F;
    private static final int CDG_COMMAND = 0x09;
    private static final int MEMORY_PRESET = 1;
    private static final int BORDER_PRESET = 2;
    private static final int TILE_BLOCK = 6;
    private static final int SCROLL_PRESET = 20;
    private static final int SCROLL_COPY = 24;
    private static final int DEFINE_TRANSPARENT = 28;
    private static final int LOAD_COLORS_LOW = 30;
    private static final int LOAD_COLORS_HIGH = 31;
    private static final int TILE_BLOCK_XOR = 38;

    /** Colour index (0-15) per screen pixel. */
    private final byte[] screen = new byte[WIDTH * HEIGHT];
    private final int[] palette = new int[16];
    private final byte[] data;
    private int position = 0; // packets processed so far
    private int hOffset = 0, vOffset = 0;
    private int borderColor = 0;
    private boolean dirty = true;

    public final int[] pixels = new int[WIDTH * HEIGHT];

    public CdgDecoder(byte[] data) {
        this.data = data;
        for (int i = 0; i < 16; i++) palette[i] = 0xFF000000;
    }

    public int totalPackets() {
        return data.length / PACKET_SIZE;
    }

    /** Process packets until the given playback time. Returns true if the picture changed. */
    public boolean advanceTo(long millis) {
        int target = (int) Math.min(totalPackets(), millis * PACKETS_PER_SECOND / 1000L);
        if (target < position) {
            reset();
        }
        while (position < target) {
            processPacket(position * PACKET_SIZE);
            position++;
        }
        if (dirty) {
            render();
            dirty = false;
            return true;
        }
        return false;
    }

    public void reset() {
        position = 0;
        hOffset = 0;
        vOffset = 0;
        borderColor = 0;
        java.util.Arrays.fill(screen, (byte) 0);
        for (int i = 0; i < 16; i++) palette[i] = 0xFF000000;
        dirty = true;
    }

    public int getBorderColor() {
        return palette[borderColor & 0x0F];
    }

    private void processPacket(int off) {
        if ((data[off] & CMD_MASK) != CDG_COMMAND) return;
        int instruction = data[off + 1] & CMD_MASK;
        int d = off + 4;
        switch (instruction) {
            case MEMORY_PRESET: memoryPreset(d); break;
            case BORDER_PRESET: borderPreset(d); break;
            case TILE_BLOCK: tileBlock(d, false); break;
            case TILE_BLOCK_XOR: tileBlock(d, true); break;
            case SCROLL_PRESET: scroll(d, false); break;
            case SCROLL_COPY: scroll(d, true); break;
            case DEFINE_TRANSPARENT: break;
            case LOAD_COLORS_LOW: loadColors(d, 0); break;
            case LOAD_COLORS_HIGH: loadColors(d, 8); break;
            default: break;
        }
    }

    private int b(int i) {
        return data[i] & 0x3F;
    }

    private void memoryPreset(int d) {
        int color = b(d) & 0x0F;
        int repeat = b(d + 1) & 0x0F;
        if (repeat != 0) return; // repeats are redundant copies
        java.util.Arrays.fill(screen, (byte) color);
        dirty = true;
    }

    private void borderPreset(int d) {
        int color = b(d) & 0x0F;
        borderColor = color;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (x < 6 || x >= 294 || y < 12 || y >= 204) {
                    screen[y * WIDTH + x] = (byte) color;
                }
            }
        }
        dirty = true;
    }

    private void tileBlock(int d, boolean xor) {
        int color0 = b(d) & 0x0F;
        int color1 = b(d + 1) & 0x0F;
        int row = b(d + 2) & 0x1F;
        int col = b(d + 3) & 0x3F;
        if (row > 17 || col > 49) return;
        int x0 = col * 6;
        int y0 = row * 12;
        for (int y = 0; y < 12; y++) {
            int bits = b(d + 4 + y);
            for (int x = 0; x < 6; x++) {
                boolean on = ((bits >> (5 - x)) & 1) != 0;
                int idx = (y0 + y) * WIDTH + (x0 + x);
                int c = on ? color1 : color0;
                if (xor) {
                    screen[idx] = (byte) ((screen[idx] ^ c) & 0x0F);
                } else {
                    screen[idx] = (byte) c;
                }
            }
        }
        dirty = true;
    }

    private void loadColors(int d, int start) {
        for (int i = 0; i < 8; i++) {
            int hi = b(d + i * 2);
            int lo = b(d + i * 2 + 1);
            int r = (hi >> 2) & 0x0F;
            int g = ((hi & 0x03) << 2) | ((lo >> 4) & 0x03);
            int bl = lo & 0x0F;
            palette[start + i] = 0xFF000000 | ((r * 17) << 16) | ((g * 17) << 8) | (bl * 17);
        }
        dirty = true;
    }

    private void scroll(int d, boolean copy) {
        int color = b(d) & 0x0F;
        int hScroll = b(d + 1);
        int vScroll = b(d + 2);
        int hCmd = (hScroll >> 4) & 0x03;
        int vCmd = (vScroll >> 4) & 0x03;
        hOffset = hScroll & 0x07;
        vOffset = vScroll & 0x0F;

        int dx = hCmd == 1 ? 6 : (hCmd == 2 ? -6 : 0);
        int dy = vCmd == 1 ? 12 : (vCmd == 2 ? -12 : 0);
        if (dx == 0 && dy == 0) {
            dirty = true;
            return;
        }
        byte[] copyBuf = screen.clone();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int sx = x - dx;
                int sy = y - dy;
                byte v;
                if (sx >= 0 && sx < WIDTH && sy >= 0 && sy < HEIGHT) {
                    v = copyBuf[sy * WIDTH + sx];
                } else if (copy) {
                    v = copyBuf[((sy + HEIGHT) % HEIGHT) * WIDTH + ((sx + WIDTH) % WIDTH)];
                } else {
                    v = (byte) color;
                }
                screen[y * WIDTH + x] = v;
            }
        }
        dirty = true;
    }

    private void render() {
        for (int y = 0; y < HEIGHT; y++) {
            int sy = y + vOffset;
            for (int x = 0; x < WIDTH; x++) {
                int sx = x + hOffset;
                int c;
                if (sx < WIDTH && sy < HEIGHT) {
                    c = screen[sy * WIDTH + sx];
                } else {
                    c = borderColor;
                }
                pixels[y * WIDTH + x] = palette[c & 0x0F];
            }
        }
    }
}
