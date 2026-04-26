@import dev.relism.flash.ext.view.jte.model.HomePage
@import java.util.Map
@param HomePage page
@param String build
@param Map<String, Object> global

<h1>${page.title()}</h1>
<p>${page.author()}</p>
<small>${build}</small>
<small>${(String) global.get("appName")}</small>
