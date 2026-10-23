import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Drives the mouse and keyboard of the display in DISPLAY, for UI checks in the sandbox IDE.
 * Coordinates are screen pixels, as in a screenshot of the display.
 *
 * <pre>
 * java tools/ide-sandbox/Robo.java move X Y
 * java tools/ide-sandbox/Robo.java click X Y          (moves first, so hover effects start)
 * java tools/ide-sandbox/Robo.java dclick X Y
 * java tools/ide-sandbox/Robo.java drag X1 Y1 X2 Y2   (for example a splitter or a tool window edge)
 * java tools/ide-sandbox/Robo.java wheel X Y NOTCHES  (positive scrolls down)
 * java tools/ide-sandbox/Robo.java keys CONTROL+F4 ESCAPE   (names of KeyEvent.VK_ constants)
 * java tools/ide-sandbox/Robo.java type "some text"   (letters, digits, space and . , - / _)
 * </pre>
 */
public class Robo {
    public static void main(String[] args) throws Exception {
        Robot robot = new Robot();
        robot.setAutoDelay(40);
        switch (args[0]) {
            case "move" -> robot.mouseMove(arg(args, 1), arg(args, 2));
            case "click", "dclick" -> {
                robot.mouseMove(arg(args, 1) - 2, arg(args, 2) - 2);
                robot.delay(150);
                robot.mouseMove(arg(args, 1), arg(args, 2));
                robot.delay(150);
                int count = args[0].equals("dclick") ? 2 : 1;
                for (int i = 0; i < count; i++) {
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                }
            }
            case "drag" -> {
                int x1 = arg(args, 1), y1 = arg(args, 2), x2 = arg(args, 3), y2 = arg(args, 4);
                robot.mouseMove(x1, y1);
                robot.delay(150);
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                for (int step = 1; step <= 20; step++) {
                    robot.mouseMove(x1 + (x2 - x1) * step / 20, y1 + (y2 - y1) * step / 20);
                }
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            }
            case "wheel" -> {
                robot.mouseMove(arg(args, 1), arg(args, 2));
                robot.delay(150);
                robot.mouseWheel(arg(args, 3));
            }
            case "keys" -> {
                for (int i = 1; i < args.length; i++) {
                    String[] names = args[i].split("\\+");
                    int[] codes = new int[names.length];
                    for (int n = 0; n < names.length; n++) codes[n] = (int) KeyEvent.class.getField("VK_" + names[n]).get(null);
                    for (int code : codes) robot.keyPress(code);
                    for (int n = codes.length - 1; n >= 0; n--) robot.keyRelease(codes[n]);
                }
            }
            case "type" -> {
                for (char c : args[1].toCharArray()) {
                    boolean shift = Character.isUpperCase(c) || c == '_';
                    int code = switch (c) {
                        case ' ' -> KeyEvent.VK_SPACE;
                        case '.' -> KeyEvent.VK_PERIOD;
                        case ',' -> KeyEvent.VK_COMMA;
                        case '-', '_' -> KeyEvent.VK_MINUS;
                        case '/' -> KeyEvent.VK_SLASH;
                        default -> KeyEvent.getExtendedKeyCodeForChar(Character.toUpperCase(c));
                    };
                    if (shift) robot.keyPress(KeyEvent.VK_SHIFT);
                    robot.keyPress(code);
                    robot.keyRelease(code);
                    if (shift) robot.keyRelease(KeyEvent.VK_SHIFT);
                }
            }
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
        robot.delay(300);
    }

    private static int arg(String[] args, int index) {
        return Integer.parseInt(args[index]);
    }
}
