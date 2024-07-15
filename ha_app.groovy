definition(
    name: "Home Assistant Device Bridge",
    namespace: "tomw",
    author: "zeev mindali",
    description: "",
    category: "Convenience",
    importUrl: "https://raw.githubusercontent.com/ymerj/HE-HA-control/main/haDeviceBridgeConfiguration.groovy",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "")

preferences
{
    page(name: "mainPage")
    page(name: "discoveryPage")
    page(name: "advOptionsPage")
}

def mainPage()
{
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true)
    {
        section("<b>Home Assistant Device Bridge</b>")
        {
            input ("ip", "text", title: "Home Assistant IP Address", description: "HomeAssistant IP Address", required: true)
            input ("port", "text", title: "Home Assistant Port", description: "HomeAssistant Port Number", required: true, defaultValue: "8123")
            input ("token", "text", title: "Home Assistant Long-Lived Access Token", description: "HomeAssistant Access Token", required: true)
            input name: "secure", type: "bool", title: "Require secure connection", defaultValue: false, required: true
            input name: "ignoreSSLIssues", type: "bool", title: "Ignore SSL Issues", defaultValue: false, required: true
            input name: "enableLogging", type: "bool", title: "Enable debug logging?", defaultValue: false, required: true
        }
        section("<b>Configuration options:</b>")
        {
            href(page: "discoveryPage", title: "<b>Discover and select devices</b>", description: "Query Home Assistant for all currently configured devices.  Then select which entities to Import to Hubitat.", params: [runDiscovery : true])
        }
        section("App Name")
        {
            label title: "Optionally assign a custom name for this app", required: false
        }
    }
}

def linkToMain()
{
    section
    {
        href(page: "mainPage", title: "<b>Return to previous page</b>", description: "")
    }
}

def discoveryPage(params)
{
    dynamicPage(name: "discoveryPage", title: "", install: true, uninstall: true)
    {
        if(wasButtonPushed("cleanupUnused"))
        {
            cullGrandchildren()
            clearButtonPushed()
        }
        if(params?.runDiscovery)
        {
            state.entityList = [:]
            def domain
            // query HA to get entity_id list
            def resp = httpGetExec(genParamsMain("states"))
            logDebug("states response = ${resp?.data}")
            
            if(resp?.data)
            {
                resp.data.each
                {
                    domain = it.entity_id?.tokenize(".")?.getAt(0)
                    if(["fan", "switch", "light", "binary_sensor", "sensor", "device_tracker", "cover", "lock", "climate", "input_boolean", "number", "input_number", "button", "input_button", "valve", "humidifier"].contains(domain))
                    {
                        state.entityList.put(it.entity_id, "${it.attributes?.friendly_name} (${it.entity_id})")
                    }
                }

                state.entityList = state.entityList.sort { it.value }
            }
        }
        section
        {
            input name: "includeList", type: "enum", title: "Select any devices to <b>include</b> from Home Assistant Device Bridge", options: state.entityList, required: false, multiple: true, offerAll: true
        }
        section("Administration option")
        {
            input(name: "cleanupUnused", type: "button", title: "Remove all child devices that are not currently selected (use carefully!)")
        }
        linkToMain()
    }
}

def cullGrandchildren()
{
    // remove all child devices that aren't currently on either filtering list
    
    def ch = getChild()
    
    ch?.getChildDevices()?.each()
    {
        def entity = it.getDeviceNetworkId()?.tokenize("-")?.getAt(1)        
        if(!includeList?.contains(entity)) { ch.removeChild(entity) }
    }
}

def logDebug(msg)
{
    if(enableLogging)
    {
        log.debug "${msg}"
    }
}

def installed()
{
    def ch = getChild()
    if(!ch)
    {
        ch = addChildDevice("ymerj", "HomeAssistant Hub Parent", now().toString(), [name: "Home Assistant Device Bridge", label: "Home Assistant Device Bridge (${ip})", isComponent: false])
    }
    
    if(ch)
    {
        // propoagate our settings to the child
        ch.updateSetting("ip", ip)
        ch.updateSetting("port", port)
        ch.updateSetting("token", token)
        ch.updateSetting("secure", secure)
        def filterListForChild = includeList?.join(",")
        ch.updateDataValue("filterList", filterListForChild)
        ch.updated()
    }
    state.remove("entityList")
}

def getChild()
{
    return getChildDevices()?.getAt(0)
}

def uninstalled()
{
    deleteChildren()
}

def deleteChildren()
{
    getChildDevices()?.each
    {
        deleteChildDevice(it.getDeviceNetworkId())
    }
}

def updated()
{
    installed()
}

void appButtonHandler(btn)
{
    // flag button pushed and let pages sort it out
    setButtonPushed(btn)
}

def setButtonPushed(btn)
{
    state.button = [btn: btn]
}

def wasButtonPushed(btn)
{
    return state.button?.btn == btn
}

def clearButtonPushed()
{
    state.remove("button")
}

def genParamsMain(suffix, body = null)
{
    def params =
        [
            uri: getBaseURI() + suffix,
            headers:
            [
                'Authorization': "Bearer ${token}",
                'Content-Type': "application/json"
            ],
            ignoreSSLIssues: ignoreSSLIssues
        ]
    
    if(body)
    {
        params['body'] = body
    }
 
    return params
}

def getBaseURI()
{
    if(secure) return "https://${ip}:${port}/api/"
    return "http://${ip}:${port}/api/"
}

def httpGetExec(params, throwToCaller = false)
{
    logDebug("httpGetExec(${params})")
    
    try
    {
        def result
        httpGet(params)
        { resp ->
            if (resp)
            {
                //logDebug("resp.data = ${resp.data}")
                result = resp
            }
        }
        return result
    }
    catch (Exception e)
    {
        logDebug("httpGetExec() failed: ${e.message}")
        //logDebug("status = ${e.getResponse().getStatus().toInteger()}")
        if(throwToCaller)
        {
            throw(e)
        }
    }
}
