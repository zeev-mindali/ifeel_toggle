


definition(
    name: "Switch Bindings",
    namespace: "zeevmindali",
    author: "Zeev Mindali",
    description: "Bind two (or more) switches together.  When bound, if either one turns on or off, the binding will make the other one also turn on/off.  The binding will also sync dimmer levels, if the switch is a dimmer, and fan states if the switch is a fan controller.",
    category: "Convenience",
	iconUrl: "",
    iconX2Url: "",
    iconX3Url: "")


preferences {
     page name: "mainPage", title: "", install: true, uninstall: true
}


def installed() {
    log.info "Installed with settings: ${settings}"
    initialize()
}


def updated() {
    log.info "Updated with settings: ${settings}"
    unsubscribe()
    initialize()
}


def initialize() {
    log.info "There are ${childApps.size()} child apps"
    childApps.each { child ->
    	log.info "Child app: ${child.label}"
    }
}


def installCheck() {
	state.appInstalled = app.getInstallationState()

	if (state.appInstalled != 'COMPLETE') {
		section{paragraph "Please hit 'Done' to install '${app.label}' parent app "}
  	}
  	else {
    	log.info "Parent Installed OK"
  	}
}


def getFormat(type, myText=""){
	if(type == "header-green") return "<div style='color:#ffffff;font-weight: bold;background-color:#81BC00;border: 1px solid;box-shadow: 2px 3px #A9A9A9'>${myText}</div>"
    if(type == "line") return "\n<hr style='background-color:#1A77C9; height: 1px; border: 0;'></hr>"
	if(type == "title") return "<h2 style='color:#1A77C9;font-weight: bold'>${myText}</h2>"
}


def display(){
	section() {
		paragraph getFormat("line")
		paragraph "<div style='color:#1A77C9;text-align:center'>Switch Bindings - zeev mindali</a></div>"
	}
}


def mainPage() {
    dynamicPage(name: "mainPage") {
    	installCheck()

		if (state.appInstalled == 'COMPLETE') {
			section(getFormat("title", "${app.label}")) {
				paragraph "Bind two (or more) switches/dimmers/fans together.  When bound, if either one turns on or off or changes level, the binding will make the others also turn on/off or adjust level. (It works a lot like a z-wave association, but it happens in the Hubitat hub, so that the hub can know/display the updated device states.)"
			}
  			section("<b>Switch Bindings:</b>") {
				app(name: "anyOpenApp", appName: "Switch Binding Instance", namespace: "zeevmindali", title: "<b>Add a new switch binding</b>", multiple: true)
			}
			display()
		}
	}
}
