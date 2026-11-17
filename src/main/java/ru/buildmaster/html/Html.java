package ru.buildmaster.html;

/** Общий вид HTML-страниц: оформление и экранирование. MC GUI рисует статичный HTML и CSS без браузера. */
public final class Html {

    private Html() { }

    public static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Значение для атрибута data-command: без переводов строк и кавычек. */
    public static String attr(String s) {
        return esc(s).replace("\n", " ").replace("\r", " ");
    }

    public static final String CSS = """
            html, body { margin: 0; padding: 0; background: transparent; font-family: Minecraft, Inter, sans-serif; color: #e8e6f0; }
            .wrap { margin: 14px; padding: 16px 20px; box-sizing: border-box; background: rgba(18, 20, 36, 0.94);
                    border: 2px solid #3b4268; border-radius: 18px; box-shadow: 0 12px 40px rgba(0,0,0,0.6); }
            h1 { margin: 0 0 2px 0; font-size: 26px; color: #ffcf5a; }
            h2 { margin: 14px 0 8px 0; font-size: 18px; color: #8fd3ff; }
            .sub { color: #9aa0c0; font-size: 14px; margin-bottom: 8px; }
            .head { display: flex; align-items: center; gap: 18px; margin-bottom: 8px; }
            .head img { width: 56px; height: 56px; border-radius: 10px; }
            .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 10px; }
            .num { font-family: Inter, sans-serif; }
            .stats { display: flex; gap: 10px; flex-wrap: wrap; }
            .stat { flex: 1; min-width: 120px; background: #23274a; border-radius: 10px; padding: 8px 12px; border: 1px solid #343a68; }
            .stat b { display: block; font-size: 19px; color: #fff; }
            .stat span { font-size: 12px; color: #9aa0c0; }
            .card { display: block; background: linear-gradient(180deg, #2b3160, #202450); border: 2px solid #424a86; border-radius: 14px;
                    padding: 10px 14px; color: #fff; text-decoration: none; cursor: pointer; }
            .card:hover { background: linear-gradient(180deg, #3a4284, #2a306a); border-color: #ffcf5a; }
            .card h3 { margin: 0 0 3px 0; font-size: 18px; color: #ffcf5a; }
            .card p { margin: 0; font-size: 13px; color: #c3c8ea; }
            .btn { display: inline-block; margin: 4px 6px 0 0; padding: 5px 10px; background: #2d6a3f; border: 2px solid #4cae6b;
                   border-radius: 8px; color: #fff; font-size: 14px; text-decoration: none; cursor: pointer; }
            .btn:hover { background: #3b8a52; border-color: #ffcf5a; }
            .btn.red { background: #7a2e35; border-color: #c4565f; }
            .btn.red:hover { background: #983a43; border-color: #ffcf5a; }
            .btn.gray { background: #3a3f63; border-color: #5a628f; }
            .btn.gray:hover { background: #4b517c; border-color: #ffcf5a; }
            table { width: 100%; border-collapse: collapse; font-size: 14px; }
            th { text-align: left; color: #8fd3ff; padding: 6px 10px; border-bottom: 2px solid #343a68; }
            td { padding: 8px 10px; border-bottom: 1px solid #2c3157; vertical-align: top; }
            .plus { color: #6fe08d; } .minus { color: #ff7b86; } .warn { color: #ffb84d; } .muted { color: #8a90b3; }
            .bar { width: 220px; height: 12px; background: #11142a; border-radius: 6px; overflow: hidden; margin-top: 4px; }
            .bar i { display: block; height: 12px; background: #4cae6b; }
            .bar i.low { background: #d9534f; }
            .foot { margin-top: 12px; display: flex; gap: 10px; }
            """;

    /** Целая страница: оформление, заголовок и тело. */
    public static String page(String title, String body) {
        return "<!DOCTYPE html>\n<html lang=\"ru\"><head><meta charset=\"utf-8\">\n"
                + "<meta name=\"ui:overlay\" content=\"rgba(8, 10, 22, 0.55) blur\">\n"
                + "<title>" + esc(title) + "</title>\n<style>\n" + CSS + "</style></head>\n<body><div class=\"wrap\">\n"
                + body + "\n</div></body></html>\n";
    }
}
