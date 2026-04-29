package gg.jte.generated.ondemand.pages;
import dev.relism.flash.ext.view.jte.model.HomePage;
@SuppressWarnings("unchecked")
public final class JtehomeGenerated {
	public static final String JTE_NAME = "pages/home.jte";
	public static final int[] JTE_LINE_INFO = {0,0,1,1,1,1,5,5,5,5,6,6,6,7,7,7,8,8,8,8,8,8,1,2,3,3,3,3};
	public static void render(gg.jte.html.HtmlTemplateOutput jteOutput, gg.jte.html.HtmlInterceptor jteHtmlInterceptor, HomePage page, String build, String appName) {
		jteOutput.writeContent("\n<h1>");
		jteOutput.setContext("h1", null);
		jteOutput.writeUserContent(page.title());
		jteOutput.writeContent("</h1>\n<p>");
		jteOutput.setContext("p", null);
		jteOutput.writeUserContent(page.author());
		jteOutput.writeContent("</p>\n<small>");
		jteOutput.setContext("small", null);
		jteOutput.writeUserContent(build);
		jteOutput.writeContent("</small>\n<small>");
		jteOutput.setContext("small", null);
		jteOutput.writeUserContent(appName);
		jteOutput.writeContent("</small>");
	}
	public static void renderMap(gg.jte.html.HtmlTemplateOutput jteOutput, gg.jte.html.HtmlInterceptor jteHtmlInterceptor, java.util.Map<String, Object> params) {
		HomePage page = (HomePage)params.get("page");
		String build = (String)params.get("build");
		String appName = (String)params.get("appName");
		render(jteOutput, jteHtmlInterceptor, page, build, appName);
	}
}
