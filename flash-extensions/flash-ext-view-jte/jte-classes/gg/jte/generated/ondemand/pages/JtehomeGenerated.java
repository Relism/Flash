package gg.jte.generated.ondemand.pages;
import dev.relism.flash.ext.view.jte.model.HomePage;
import java.util.Map;
@SuppressWarnings("unchecked")
public final class JtehomeGenerated {
	public static final String JTE_NAME = "pages/home.jte";
	public static final int[] JTE_LINE_INFO = {0,0,1,2,2,2,2,6,6,6,6,7,7,7,8,8,8,9,9,9,10,10,10,2,3,4,4,4,4};
	public static void render(gg.jte.html.HtmlTemplateOutput jteOutput, gg.jte.html.HtmlInterceptor jteHtmlInterceptor, HomePage page, String build, Map<String, Object> global) {
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
		jteOutput.writeUserContent((String) global.get("appName"));
		jteOutput.writeContent("</small>\n");
	}
	public static void renderMap(gg.jte.html.HtmlTemplateOutput jteOutput, gg.jte.html.HtmlInterceptor jteHtmlInterceptor, java.util.Map<String, Object> params) {
		HomePage page = (HomePage)params.get("page");
		String build = (String)params.get("build");
		Map<String, Object> global = (Map<String, Object>)params.get("global");
		render(jteOutput, jteHtmlInterceptor, page, build, global);
	}
}
