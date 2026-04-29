@import dev.relism.flash.ext.view.jte.model.HomePage
@param HomePage page
@param String build
@param String appName

<h1>${page.title()}</h1>
<p>${page.author()}</p>
<small>${build}</small>
<small>${appName}</small>