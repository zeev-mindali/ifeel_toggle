import groovy.json.JsonSlurper
import groovy.json.JsonOutput

metadata {
    definition (name: "HomeAssistant Hub Parent", namespace: "ymerj", author: "Yves Mercier", importUrl: "https://raw.githubusercontent.com/ymerj/HE-HA-control/main/HA%20parent.groovy") {
        capability "Initialize"
        capability "Actuator"

        command "closeConnection"        
        command "callService", [[name:"entity", type:"STRING", description:"domain.entity"],[name:"service", type:"STRING"],[name:"data", type:"STRING", description:"key:value,key:value... etc"]]
	    
        attribute "Connection", "string"
    }

    preferences {
        input ("ip", "text", title: "IP", description: "HomeAssistant IP Address", required: true)
        input ("port", "text", title: "Port", description: "HomeAssistant Port Number", required: true, defaultValue: "8123")
        input ("token", "text", title: "Token", description: "HomeAssistant Long-Lived Access Token", required: true)
        input ("secure", "bool", title: "Require secure connection (https)", defaultValue: false)
        input ("logEnable", "bool", title: "Enable debug logging", defaultValue: true)
        input ("txtEnable", "bool", title: "Enable description text logging", defaultValue: true)
    }
}

def removeChild(entity){
    String thisId = device.id
    def ch = getChildDevice("${thisId}-${entity}")
    if (ch) {deleteChildDevice("${thisId}-${entity}")}
}

def logsOff(){
    log.warn("debug logging disabled...")
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

def updated(){
    log.info("updated...")
    log.warn("debug logging is: ${logEnable == true}")
    log.warn("description logging is: ${txtEnable == true}")
    unschedule()
    if (logEnable) runIn(1800,logsOff)
    initialize()
}

def initialize() {
    log.info("initializing...")
    closeConnection()

    state.id = 2
    def connectionType = "ws"
    if (secure) connectionType = "wss"
    auth = '{"type":"auth","access_token":"' + "${token}" + '"}'
    def subscriptionsList = device.getDataValue("filterList")
    if(subscriptionsList == null) return
    evenements = '{"id":1,"type":"subscribe_trigger","trigger":{"platform":"state","entity_id":"' + subscriptionsList + '"}}'
    try {
        interfaces.webSocket.connect("${connectionType}://${ip}:${port}/api/websocket", ignoreSSLIssues: true)
        interfaces.webSocket.sendMessage("${auth}")
        interfaces.webSocket.sendMessage("${evenements}")
    } 
    catch(e) {
        log.error("initialize error: ${e.message}")
    }
}

def uninstalled() {
    log.info("uninstalled...")
    closeConnection()
    unschedule()
    deleteAllChildDevices()
}

def webSocketStatus(String status){
    if (logEnable) log.debug("webSocket ${status}")

    if ((status == "status: closing") && (state.wasExpectedClose)) {
        state.wasExpectedClose = false
        sendEvent(name: "Connection", value: "Closed")
        return
    } 
    else if(status == 'status: open') {
        log.info("websocket is open")
        // success! reset reconnect delay
        pauseExecution(1000)
        state.reconnectDelay = 1
        state.wasExpectedClose = false
        sendEvent(name: "Connection", value: "Open")
    } 
    else {
        log.warn("WebSocket error, reconnecting.")
        sendEvent(name: "Connection", value: "Reconnecting")
        reconnectWebSocket()
    }
}

def reconnectWebSocket() {
    // first delay is 2 seconds, doubles every time
    state.reconnectDelay = (state.reconnectDelay ?: 1) * 2
    // don't let delay get too crazy, max it out at 10 minutes
    if(state.reconnectDelay > 600) state.reconnectDelay = 600

    //If the Home Assistant Hub is offline, give it some time before trying to reconnect
    runIn(state.reconnectDelay, initialize)
}

def parse(String description) {
    if (logEnable) log.debug("parse(): description = ${description}")
    def response = null;
    try{
        response = new groovy.json.JsonSlurper().parseText(description)
	
	if (response.type != "event") return
	def newState = response?.event?.variables?.trigger?.to_state
	if (newState?.state?.toLowerCase() == "unknown") return
        
        def origin = "physical"
        if (newState?.context?.user_id) origin = "digital"
        
        def newVals = []
        def entity = response?.event?.variables?.trigger?.entity_id        
        def domain = entity?.tokenize(".")?.getAt(0)
        def device_class = newState?.attributes?.device_class
        def friendly = newState?.attributes?.friendly_name

        newVals << newState?.state
        def mapping = null
        
        if (logEnable) log.debug("parse: domain: ${domain}, device_class: ${device_class}, entity: ${entity}, newVals: ${newVals}, friendly: ${friendly}")
        
        switch (domain) {
            case "fan":
                def speed = newState?.attributes?.speed?.toLowerCase()
                choices =  ["low","medium-low","medium","medium-high","high","auto"]
                if (!(choices.contains(speed)))
                    {
                    if (logEnable) log.info("Invalid fan speed received - ${speed}")
                    speed = null
                    }
                def percentage = newState?.attributes?.percentage
                switch (percentage.toInteger()) {
                    case 0: 
                        speed = "off"
                        break
                    case 25: 
                        speed = "low"
                        break
                    case 50: 
                        speed = "medium"
                        break
                    case 75: 
                        speed = "medium-high"
                        break
                    case 100: 
                        speed = "high"
                    default:
                        if (logEnable) log.info("Invalid fan percentage received - ${percentage}")
                }
                newVals += speed
                newVals += percentage
                mapping = translateDevices(domain, newVals, friendly, origin)
                if (!speed) mapping.event.remove(1)
                if (mapping) updateChildDevice(mapping, entity, friendly)
                break
            case "cover":
                def pos = newState?.attributes?.current_position?.toInteger()
                newVals += pos
                def tilt = newState?.attributes?.current_tilt_position?.toInteger() // or perhaps newState?.attributes?.current_cover_tilt_position?.toInteger(). Ambiguity in the docs.
                newVals += tilt
                switch (device_class) {
                   case {it in ["blind","shutter","window"]}:
                       device_class = "blind"
                       break
                   case {it in ["curtain","shade"]}:
                       device_class = "shade"
                       break
                   case "garage":
                       break
                   default:
                       device_class = "door"
                }
                mapping = translateCovers(device_class, newVals, friendly, origin)
                if (mapping) updateChildDevice(mapping, entity, friendly)
                break
            case "lock":
            case "device_tracker":
            case "valve":
            case "switch":
            case "input_boolean":
                mapping = translateDevices(domain, newVals, friendly, origin)
                if (mapping) updateChildDevice(mapping, entity, friendly)
                break
            case "light":
                def level = newState?.attributes?.brightness
                if (level) level = Math.round((level.toInteger() * 100 / 255))
                newVals += level
                def hue = newState?.attributes?.hs_color?.getAt(0)
                if (hue) hue = Math.round(hue.toInteger() * 100 / 360)
                def sat = newState?.attributes?.hs_color?.getAt(1)
                if (sat) sat = Math.round(sat.toInteger())
                def ct = newState?.attributes?.color_temp
                if (ct) ct = Math.round(1000000/ct)
                def effectsList = []
                effectsList = newState?.attributes?.effect_list?.indexed(1)
                def effectName = newState?.attributes?.effect
                def lightType = []
                lightType = newState?.attributes?.supported_color_modes
                if ((lightType.intersect(["hs", "rgb"])) && (lightType.contains("color_temp"))) lightType += "rgbw"
                if (effectsList) lightType += "rgbwe"
                switch (lightType) {
                    case {it.intersect(["rgbwe"])}:
                        device_class = "rgbwe"
                        newVals += ["RGB", hue, sat, ct, effectsList, effectName]
                        break
                    case {it.intersect(["rgbww", "rgbw"])}:
                        device_class = "rgbw"
                        newVals += ["RGB", hue, sat, ct]
                        break
                    case {it.intersect(["hs", "rgb"])}:
                        device_class = "rgb"
                        newVals += ["RGB", hue, sat]
                        break
                    case {it.intersect(["color_temp"])}:
                        device_class = "ct"
                        newVals += ["white", ct]
                        break
                    default:
                        device_class = "dimmer"
                    }
                mapping = translateLight(device_class, newVals, friendly, origin)
                if (newVals[0] == "off") { // remove updates not provided with the HA 'off' event json data
                   for(int i in (mapping.event.size - 1)..1) {
                       mapping.event.remove(i)
                       }  
                    }
                if (mapping) updateChildDevice(mapping, entity, friendly)
                break
            case "binary_sensor":
                mapping = translateBinarySensors(device_class, newVals, friendly, origin)
                if (mapping) updateChildDevice(mapping, entity, friendly)
                break
            case "input_number":
            case "number":
                def minimum = newState?.attributes?.min
                def maximum = newState?.attributes?.max
                def step = newState?.attributes?.step
                def unit = newState?.attributes?.unit_of_measurement
                newVals += [unit, minimum, maximum, step]
                mapping = translateDevices(domain, newVals, friendly, origin)
                if (mapping) updateChildDevice(mapping, entity, friendly)
                break
            case "sensor":
                def unit_of_measurement = newState?.attributes?.unit_of_measurement
                if ((!device_class) && (unit_of_measurement in ["Bq/m³","pCi/L"])) device_class = "radon" // if there is no device_class, we need to infer from the units
                newVals << unit_of_measurement
                mapping = translateSensors(device_class, newVals, friendly, origin)
                if (mapping) updateChildDevice(mapping, entity, friendly)
                break
            case "climate":
                def thermostat_mode = newState?.state
                def current_temperature = newState?.attributes?.current_temperature
                def current_humidity = newState?.attributes?.current_humidity
                def hvac_action = newState?.attributes?.hvac_action
                def fan_mode = newState?.attributes?.fan_mode
                def target_temperature = newState?.attributes?.temperature
                def target_temp_high = newState?.attributes?.target_temp_high
                def target_temp_low = newState?.attributes?.target_temp_low
                def hvac_modes = newState?.attributes?.hvac_modes
                if (hvac_modes)
                    {
                    hvac_modes = hvac_modes.minus(["auto"])
                    if (hvac_modes.contains("heat_cool")) hvac_modes = hvac_modes - "heat_cool" + "auto"
                    hvac_modes = hvac_modes.intersect(["auto", "off", "heat", "emergency heat", "cool"])
                    }
                else
                    {
                    hvac_modes = ["heat"]
                    }
                def supportedTmodes = JsonOutput.toJson(hvac_modes)
                def fan_modes = newState?.attributes?.fan_modes
                if (fan_modes)
                    {
                    if (fan_modes.minus(["auto", "on"])) fan_modes = fan_modes + "circulate"
                    fan_modes = fan_modes.intersect(["auto", "on", "circulate"])
                    }
                else
                    {
                    fan_modes = ["on"]
                    }
                def supportedFmodes = JsonOutput.toJson(fan_modes)
                switch (fan_mode) {
                    case "off":
                        thermostat_mode = "off"
                        break
                    case "auto":
                        break
                    default:
                    	fan_mode = "on"
                }
                switch (thermostat_mode) {
                    case "dry":
                    case "auto":
                        return
                        break
                    case "fan_only":
                        fan_mode = "circulate"
                        break
                    case "heat_cool":
                        thermostat_mode = "auto"
                        break
                }
                switch (hvac_action) {
                    case "drying":
                        return
                        break
                    case "off":
                        hvac_action = "idle"
                        break
                    case "fan":
                        hvac_action = "fan only"
                        break
                    case "preheating":
                        hvac_action = "pending heat"
                        break
                }
                newVals = [thermostat_mode, current_temperature, hvac_action, fan_mode, target_temperature, target_temp_high, target_temp_low, supportedTmodes, supportedFmodes, current_humidity]
                mapping = translateDevices(domain, newVals, friendly, origin)
                if (!current_humidity) mapping.event.remove(9) // some thermostats don't provide humidity reading
                if (mapping) updateChildDevice(mapping, entity, friendly)
                break
            case "button":
            case "input_button":
                newVals = [1]
                mapping = translateDevices(domain, newVals, friendly, origin)
                if (mapping) updateChildDevice(mapping, entity, friendly)
                break
            case "humidifier":
                humidifierMode = newState?.attributes?.mode
                def supportedModes = []
                supportedModes = newState?.attributes?.available_modes?.indexed(1)
                def maxHumidity = newState?.attributes?.max_humidity
                def minHumidity = newState?.attributes?.min_humidity
                def currentHumidity = newState?.attributes?.current_humidity
                def targetHumidity = newState?.attributes?.target_humidity
                newVals += [humidifierMode, supportedModes, maxHumidity, minHumidity, currentHumidity, targetHumidity]
                mapping = translateDevices(domain, newVals, friendly, origin)
                if (!targetHumidity) mapping.event.remove(6)
                if (!currentHumidity) mapping.event.remove(5)
                if (mapping) updateChildDevice(mapping, entity, friendly)
                break
            default:
                if (logEnable) log.info("No mapping exists for domain: ${domain}, device_class: ${device_class}.  Please contact devs to have this added.")
        }
        return
    }  
    catch(e) {
        log.error("Parsing error: ${e}")
        return
    }
}

def translateBinarySensors(device_class, newVals, friendly, origin)
{
    def mapping =
        [
            door: [type: "Generic Component Contact Sensor",            event: [[name: "contact", value: newVals[0] == "on" ? "open":"closed", descriptionText: "${friendly} is ${newVals[0] == 'on' ? 'open':'closed'}"]]],
            garage_door: [type: "Generic Component Contact Sensor",     event: [[name: "contact", value: newVals[0] == "on" ? "open":"closed", descriptionText:"${friendly} is ${newVals[0] == 'on' ? 'open':'closed'}"]]],
            lock: [type: "Generic Component Contact Sensor",            event: [[name: "contact", value: newVals[0] == "on" ? "open":"closed", descriptionText:"${friendly} is ${newVals[0] == 'on' ? 'unlocked':'locked'}"]]],
            moisture: [type: "Generic Component Water Sensor",          event: [[name: "water", value: newVals[0] == "on" ? "wet":"dry", descriptionText:"${friendly} is ${newVals[0] == 'on' ? 'wet':'dry'}"]]],
            motion: [type: "Generic Component Motion Sensor",           event: [[name: "motion", value: newVals[0] == "on" ? """active""":"""inactive""", descriptionText:"${friendly} is ${newVals[0] == 'on' ? 'active':'inactive'}"]]],
            occupancy: [type: "Generic Component Motion Sensor",        event: [[name: "motion", value: newVals[0] == "on" ? """active""":"""inactive""", descriptionText:"${friendly} is ${newVals[0] == 'on' ? 'active':'inactive'}"]]],
            moving: [type: "Generic Component Acceleration Sensor",     event: [[name: "acceleration", value: newVals[0] == "on" ? """active""":"""inactive""", descriptionText:"${friendly} is ${newVals[0] == 'on' ? 'active':'inactive'}"]]],
            opening: [type: "Generic Component Contact Sensor",         event: [[name: "contact", value: newVals[0] == "on" ? "open":"closed", descriptionText:"${friendly} is ${newVals[0] == 'on' ? 'open':'closed'}"]]],
            presence: [type: "Generic Component Presence Sensor",       event: [[name: "presence", value: newVals[0] == "on" ? "present":"not present", descriptionText:"${friendly} is ${newVals[0] == 'on' ? 'present':'not present'}"]], namespace: "community"],
            smoke: [type: "Generic Component Smoke Detector",           event: [[name: "smoke", value: newVals[0] == "on" ? "detected":"clear", descriptionText:"${friendly} is ${newVals[0] == 'on' ? 'detected':'clear'}"]]],
            vibration: [type: "Generic Component Acceleration Sensor",  event: [[name: "acceleration", value: newVals[0] == "on" ? """active""":"""inactive""", descriptionText:"${friendly} is ${newVals[0] == 'on' ? 'active':'inactive'}"]]],
            unknown: [type: "Generic Component Unknown Sensor",         event: [[name: "unknown", value: newVals[0], descriptionText:"${friendly} is ${newVals[0]}"]], namespace: "community"],
            window: [type: "Generic Component Contact Sensor",          event: [[name: "contact", value: newVals[0] == "on" ? "open":"closed", descriptionText:"${friendly} is ${newVals[0] == 'on' ? 'open':'closed'}"]]],
        ]
    if (!mapping[device_class]) device_class = "unknown"
    return mapping[device_class]
}

def translateSensors(device_class, newVals, friendly, origin)
{
    def mapping =
        [
            humidity: [type: "Generic Component Humidity Sensor",             event: [[name: "humidity", value: newVals[0], unit: newVals[1] ?: "%", descriptionText:"${friendly} humidity is ${newVals[0]} ${newVals[1] ?: '%'}"]]],
            moisture: [type: "Generic Component Humidity Sensor",             event: [[name: "humidity", value: newVals[0], unit: newVals[1] ?: "%", descriptionText:"${friendly} humidity is ${newVals[0]} ${newVals[1] ?: '%'}"]]],
            illuminance: [type: "Generic Component Illuminance Sensor",       event: [[name: "illuminance", value: newVals[0], unit: newVals[1] ?: "lx", descriptionText:"${friendly} illuminance is ${newVals[0]} ${newVals[1] ?: 'lx'}"]], namespace: "community"],
            battery: [type: "Generic Component Battery",                      event: [[name: "battery", value: newVals[0], unit: newVals[1] ?: "%", descriptionText:"${friendly} battery is ${newVals[0]} ${newVals[1] ?: '%'}"]], namespace: "community"],
            power: [type: "Generic Component Power Meter",                    event: [[name: "power", value: newVals[0], unit: newVals[1] ?: "W", descriptionText:"${friendly} power is ${newVals[0]} ${newVals[1] ?: 'W'}"]]],
            pressure: [type: "Generic Component Pressure Sensor",             event: [[name: "pressure", value: newVals[0], unit: newVals[1] ?: "", descriptionText:"${friendly} pressure is ${newVals[0]} ${newVals[1] ?: ''}"]], namespace: "community"],
            carbon_dioxide: [type: "Generic Component Carbon Dioxide Sensor", event: [[name: "carbonDioxide", value: newVals[0], unit: newVals[1] ?: "ppm", descriptionText:"${friendly} carbon_dioxide is ${newVals[0]} ${newVals[1] ?: 'ppm'}"]], namespace: "community"],
            volatile_organic_compounds_parts: [type: "Generic Component Volatile Organic Compounds Sensor",
                                                                              event: [[name: "voc", value: newVals[0], unit: newVals[1] ?: "ppb", descriptionText:"${friendly} volatile_organic_compounds_parts is ${newVals[0]} ${newVals[1] ?: 'ppb'}"]], namespace: "community"],
            volatile_organic_compounds: [type: "Generic Component Volatile Organic Compounds Sensor",
                                                                              event: [[name: "voc", value: newVals[0], unit: newVals[1] ?: "µg/m³", descriptionText:"${friendly} volatile_organic_compounds is ${newVals[0]} ${newVals[1] ?: 'µg/m³'}"]], namespace: "community"],
            radon: [type: "Generic Component Radon Sensor",                   event: [[name: "radon", value: newVals[0], unit: newVals[1], descriptionText:"${friendly} radon is ${newVals[0]} ${newVals[1]}"]], namespace: "community"],
            temperature: [type: "Generic Component Temperature Sensor",       event: [[name: "temperature", value: newVals[0], unit: newVals[1] ?: "°", descriptionText:"${friendly} temperature is ${newVals[0]} ${newVals[1] ?: '°'}"]]],
            voltage: [type: "Generic Component Voltage Sensor",               event: [[name: "voltage", value: newVals[0], unit: newVals[1] ?: "V", descriptionText:"${friendly} voltage is ${newVals[0]} ${newVals[1] ?: 'V'}"]]],
            energy: [type: "Generic Component Energy Meter",                  event: [[name: "energy", value: newVals[0], unit: newVals[1] ?: "kWh", descriptionText:"${friendly} energy is ${newVals[0]} ${newVals[1] ?: 'kWh'}"]]],
            unknown: [type: "Generic Component Unknown Sensor",               event: [[name: "unknown", value: newVals[0], unit: newVals[1] ?: "", descriptionText:"${friendly} value is ${newVals[0]} ${newVals[1] ?: ''}"]], namespace: "community"],
            timestamp: [type: "Generic Component TimeStamp Sensor",           event: [[name: "timestamp", value: newVals[0], descriptionText:"${friendly} time is ${newVals[0]}"]], namespace: "community"],
            pm25: [type: "Generic Component pm25 Sensor",                     event: [[name: "pm25", value: newVals[0], unit: newVals[1] ?: "µg/m³", descriptionText:"${friendly} pm2.5 is ${newVals[0]} ${newVals[1] ?: 'µg/m³'}"]], namespace: "community"],
        ]
    if (!mapping[device_class]) device_class = "unknown"
    return mapping[device_class]
}

def translateCovers(device_class, newVals, friendly, origin)
{
    def mapping =
        [
            shade: [type: "Generic Component Window Shade",             event: [[name: "windowShade", value: newVals[0] ?: "unknown", type: origin, descriptionText:"${friendly} was turned ${newVals[0]} [${origin}]"],[name: "position", value: (null != newVals?.getAt(1)) ? newVals[1] : "unknown", type: origin, descriptionText:"${friendly} was turned ${newVals[1]} [${origin}]"]], namespace: "community"],
            garage: [type: "Generic Component Garage Door Control",     event: [[name: "door", value: newVals[0] ?: "unknown", type: origin, descriptionText:"${friendly} was turned ${newVals[0]} [${origin}]"]], namespace: "community"],
            door: [type: "Generic Component Door Control",              event: [[name: "door", value: newVals[0] ?: "unknown", type: origin, descriptionText:"${friendly} was turned ${newVals[0]} [${origin}]"]], namespace: "community"],
            blind: [type: "Generic Component Window Blind",             event: [[name: "windowBlind", value: newVals[0] ?: "unknown", type: origin, descriptionText:"${friendly} was turned ${newVals[0]} [${origin}]"],[name: "position", value: newVals[1] ?: "unknown", type: origin, descriptionText:"${friendly} position was set to ${newVals[1] ?: "unknown"} [${origin}]"],[name: "tilt", value: newVals[2] ?: "unknown", type: origin, descriptionText:"${friendly} tilt was set to ${newVals[2] ?: "unknown"} [${origin}]"]], namespace: "community"],
        ]
    return mapping[device_class]
}

def translateDevices(domain, newVals, friendly, origin)
{
    def mapping =
        [
            button: [type: "Generic Component Pushable Button",         event: [[name: "push", value: newVals[0], type: origin, descriptionText:"${friendly} button ${newVals[0]} was pushed [${origin}]"]], namespace: "community"],
            input_button: [type: "Generic Component Pushable Button",   event: [[name: "push", value: newVals[0], type: origin, descriptionText:"${friendly} button ${newVals[0]} was pushed [${origin}]"]], namespace: "community"],
            fan: [type: "Generic Component Fan Control",                event: [[name: "switch", value: newVals[0], type: origin, descriptionText:"${friendly} was turned ${newVals[0]} [${origin}]"],[name: "speed", value: newVals[1], type: origin, descriptionText:"${friendly} speed was set to ${newVals[1]} [${origin}]"],[name: "level", value: newVals[2], type: origin, descriptionText:"${friendly} level was set to ${newVals[2]} [${origin}]"]]],
            switch: [type: "Generic Component Switch",                  event: [[name: "switch", value: newVals[0], type: origin, descriptionText:"${friendly} was turned ${newVals[0]} [${origin}]"]]],
            device_tracker: [type: "Generic Component Presence Sensor", event: [[name: "presence", value: newVals[0] == "home" ? "present":"not present", descriptionText:"${friendly} is updated"]], namespace: "community"],
            lock: [type: "Generic Component Lock",                      event: [[name: "lock", value: newVals[0] ?: "unknown", type: origin, descriptionText:"${friendly} was turned ${newVals[0]} [${origin}]"]]],
            climate: [type: "HADB Generic Component Thermostat",        event: [[name: "thermostatMode", value: newVals[0], descriptionText: "${friendly} is set to ${newVals[0]}"],[name: "temperature", value: newVals[1], descriptionText: "${friendly}'s current temperature is ${newVals[1]} degree"],[name: "thermostatOperatingState", value: newVals[2], descriptionText: "${friendly}'s mode is ${newVals[2]}"],[name: "thermostatFanMode", value: newVals[3], descriptionText: "${friendly}'s fan is set to ${newVals[3]}"],[name: "thermostatSetpoint", value: newVals[4], descriptionText: "${friendly}'s temperature is set to ${newVals[4]} degree"],[name: "coolingSetpoint", value: newVals[5] ?: newVals[4], descriptionText: "${friendly}'s cooling temperature is set to ${newVals[5] ?: newVals[4]} degrees"],[name: "heatingSetpoint", value: newVals[6] ?: newVals[4], descriptionText: "${friendly}'s heating temperature is set to ${newVals[6] ?: newVals[4]} degrees"],[name: "supportedThermostatModes", value: newVals[7], descriptionText: "${friendly} supportedThermostatModes were set to ${newVals[7]}"],[name: "supportedThermostatFanModes", value: newVals[8], descriptionText: "${friendly} supportedThermostatFanModes were set to ${newVals[8]}"],[name: "humidity", value: newVals[9], unit: "%", descriptionText:"${friendly} humidity is ${newVals[9]}%"]], namespace: "community"],
            input_boolean: [type: "Generic Component Switch",           event: [[name: "switch", value: newVals[0], type: origin, descriptionText:"${friendly} was turned ${newVals[0]} [${origin}]"]]],
            humidifier: [type: "HADB Generic Component Humidifier",     event: [[name: "switch", value: newVals[0], type: origin, descriptionText:"${friendly} was turned ${newVals[0]} [${origin}]"],[name: "humidifierMode", value: newVals[1], descriptionText: "${friendly}'s humidifier is set to ${newVals[1]}"],[name: "supportedModes", value: newVals[2], descriptionText: "${friendly} supportedModes were set to ${newVals[2]}"],[name: "maxHumidity", value: newVals[3] ?: 100, descriptionText:"${friendly} max humidity is ${newVals[3] ?: 100}"],[name: "minHumidity", value: newVals[4] ?: 0, descriptionText:"${friendly} min humidity is ${newVals[4] ?: 0}"],[name: "humidity", value: newVals[5], unit: "%", descriptionText:"${friendly} current humidity is ${newVals[5]}%"],[name: "targetHumidity", value: newVals[6], unit: "%", descriptionText:"${friendly} target humidity is set to ${newVals[6]}%"]], namespace: "community"],
            valve: [type: "HADB Generic Component Valve",               event: [[name: "valve", value: newVals[0] == "closed" ? "closed":"open", type: origin, descriptionText:"${friendly} was turned ${newVals[0]} [${origin}]"]], namespace: "community"],
            input_number: [type: "Generic Component Number",            event: [[name: "number", value: newVals[0], unit: newVals[1] ?: "", type: origin, descriptionText:"${friendly} was set to ${newVals[0]} ${newVals[1] ?: ''} [${origin}]"],[name: "minimum", value: newVals[2], descriptionText:"${friendly} minimum value is ${newVals[2]}"],[name: "maximum", value: newVals[3], descriptionText:"${friendly} maximum value is ${newVals[3]}"],[name: "step", value: newVals[4], descriptionText:"${friendly} step is ${newVals[4]}"]], namespace: "community"],
            number: [type: "Generic Component Number",                  event: [[name: "number", value: newVals[0], unit: newVals[1] ?: "", type: origin, descriptionText:"${friendly} was set to ${newVals[0]} ${newVals[1] ?: ''} [${origin}]"],[name: "minimum", value: newVals[2], descriptionText:"${friendly} minimum value is ${newVals[2]}"],[name: "maximum", value: newVals[3], descriptionText:"${friendly} maximum value is ${newVals[3]}"],[name: "step", value: newVals[4], descriptionText:"${friendly} step is ${newVals[4]}"]], namespace: "community"],
        ]
    return mapping[domain]
}

def translateLight(device_class, newVals, friendly, origin)
{
    def mapping =
        [
            rgbwe: [type: "Generic Component RGBW Light Effects",       event: [[name: "switch", value: newVals[0], type: origin, descriptionText:"${friendly} was turned ${newVals[0]} [${origin}]"],[name: "level", value: newVals[1], type: origin, descriptionText:"${friendly} level was set to ${newVals[1]}"],[name: "colorMode", value: newVals[2], descriptionText:"${friendly} color mode was set to ${newVals[2]}"],[name: "hue", value: newVals[3], descriptionText:"${friendly} hue was set to ${newVals[3]}"],[name: "saturation", value: newVals[4], descriptionText:"${friendly} saturation was set to ${newVals[4]}"],[name: "colorTemperature", value: newVals[5] ?: 'emulated', descriptionText:"${friendly} color temperature was set to ${newVals[5] ?: 'emulated'}°K"],[name: "lightEffects", value: newVals[6]],[name: "effectName", value: newVals[7] ?: "none", descriptionText:"${friendly} effect was set to ${newVals[7] ?: 'none'}"]]],
            rgbw: [type: "Generic Component RGBW",                      event: [[name: "switch", value: newVals[0], type: origin, descriptionText:"${friendly} was turned ${newVals[0]} [${origin}]"],[name: "level", value: newVals[1], type: origin, descriptionText:"${friendly} level was set to ${newVals[1]}"],[name: "colorMode", value: newVals[2], descriptionText:"${friendly} color mode was set to ${newVals[2]}"],[name: "hue", value: newVals[3], descriptionText:"${friendly} hue was set to ${newVals[3]}"],[name: "saturation", value: newVals[4], descriptionText:"${friendly} saturation was set to ${newVals[4]}"],[name: "colorTemperature", value: newVals[5], descriptionText:"${friendly} color temperature was set to ${newVals[5]}°K"]]],
            rgb: [type: "Generic Component RGB",                        event: [[name: "switch", value: newVals[0], type: origin, descriptionText:"${friendly} was turned ${newVals[0]} [${origin}]"],[name: "level", value: newVals[1], type: origin, descriptionText:"${friendly} level was set to ${newVals[1]}"],[name: "colorMode", value: newVals[2], descriptionText:"${friendly} color mode was set to ${newVals[2]}"],[name: "hue", value: newVals[3], descriptionText:"${friendly} hue was set to ${newVals[3]}"],[name: "saturation", value: newVals[4], descriptionText:"${friendly} saturation was set to ${newVals[4]}"]]],
            ct: [type: "Generic Component CT",                          event: [[name: "switch", value: newVals[0], type: origin, descriptionText:"${friendly} was turned ${newVals[0]} [${origin}]"],[name: "level", value: newVals[1], type: origin, descriptionText:"${friendly} level was set to ${newVals[1]}"],[name: "colorName", value: newVals[2], descriptionText:"${friendly} color name was set to ${newVals[2]}"],[name: "colorTemperature", value: newVals[3], descriptionText:"${friendly} color temperature was set to ${newVals[3]}°K"]]],
            dimmer: [type: "Generic Component Dimmer",                  event: [[name: "switch", value: newVals[0], type: origin, descriptionText:"${friendly} was turned ${newVals[0]} [${origin}]"],[name: "level", value: newVals[1], type: origin, descriptionText:"${friendly} level was set to ${newVals[1]} [${origin}]"]]],
        ]
    return mapping[device_class]
}
   
def updateChildDevice(mapping, entity, friendly) {
    def ch = createChild(mapping.type, entity, friendly, mapping.namespace)
    if (!ch) {
        log.warn("Child type: ${mapping.type} not created for entity: ${entity}")
        return
    }
    else {
        if (mapping.event[0].value == "unavailable") mapping.event = [[name: "healthStatus", value: "offline", descriptionText:"${friendly} is offline"]]
        else mapping.event += [name: "healthStatus", value: "online", descriptionText:"${friendly} is online"]
        ch.parse(mapping.event)
    }
}

def createChild(deviceType, entity, friendly, namespace = null) {
    def ch = getChildDevice("${device.id}-${entity}")
    if (!ch) ch = addChildDevice(namespace ?: "hubitat", deviceType, "${device.id}-${entity}", [name: "${entity}", label: "${friendly}", isComponent: false])
    return ch
}

def componentOn(ch) {
    if (logEnable) log.info("received on request from ${ch.label}")
    if (!ch.currentValue("level") || ch.hasCapability("LightEffects")) {
        data = [:]
    }
    else {
        data = [brightness_pct: "${ch.currentValue("level")}"]
    }
    executeCommand(ch, "turn_on", data)
}

def componentOff(ch) {
    if (logEnable) log.info("received off request from ${ch.label}")
    if(ch.getSupportedAttributes().contains("thermostatMode")) { // since componentOff() is not unique across Hubitat device types, catch this special case
        componentOffTStat(ch)
        return
    }
    executeCommand(ch, "turn_off", [:])
}

def componentSetLevel(ch, level, transition=1) {
    if (logEnable) log.info("received setLevel request from ${ch.label}")
    if (level > 100) level = 100
    if (level < 0) level = 0
    if (ch.currentValue("speed")) { // if a Fan device, special handling
        switch (level.toInteger()) {
            case 0:
                componentSetSpeed(ch, "off")
            break
            case 1..25:
                componentSetSpeed(ch, "low")
            break
            case 26..50:
                componentSetSpeed(ch, "medium")
            break
            case 51..75:
                componentSetSpeed(ch, "medium-high")
            break
            case 76..100:
                componentSetSpeed(ch, "high")
            break
            default:
                if (logEnable) log.info("No case defined for Fan setLevel(${level})")
        }
    } 
    else {        
        data = [brightness_pct: "${level}", transition: "${transition}"]
        executeCommand(ch, "turn_on", data)
    }
}

def componentSetColor(ch, color, transition=1) {
    if (logEnable) log.info("received setColor request from ${ch.label}")
    convertedHue = Math.round(color.hue * 360/100)
    data = [brightness_pct: "${color.level}", hs_color: ["${convertedHue}", "${color.saturation}"], transition: "${transition}"]
    executeCommand(ch, "turn_on", data)
}

def componentSetColorTemperature(ch, colortemperature, level, transition=1) {
    if (logEnable) log.info("received setColorTemperature request from ${ch.label}")
    if (!level) level = ch.currentValue("level")
    if (!transition) transition = 1
    data = [brightness_pct: "${level}", color_temp_kelvin: "${colortemperature}", transition: "${transition}"]
    executeCommand(ch, "turn_on", data)
}

def componentSetHue(ch, hue, transition=1) {
    if (logEnable) log.info("received setHue request from ${ch.label}")
    convertedHue = Math.round(hue * 360/100)
    data = [brightness_pct: "${ch.currentValue("level")}", hs_color: ["${convertedHue}", "${ch.currentValue("saturation")}"], transition: "${transition}"]
    executeCommand(ch, "turn_on", data)
}

def componentSetSaturation(ch, saturation, transition=1) {
    if (logEnable) log.info("received setSaturation request from ${ch.label}")
    convertedHue = Math.round(ch.currentValue("hue") * 360/100)
    data = [brightness_pct: "${ch.currentValue("level")}", hs_color: ["${convertedHue}", "${saturation}"], transition: "${transition}"]
    executeCommand(ch, "turn_on", data)
}

def componentSetEffect(ch, effectNumber) {
    if (logEnable) log.info("received setEffect request from ${ch.label}")
    def effectsList = ch.currentValue("lightEffects")?.tokenize(',=[]')
    def max = effectsList.size() / 2
    max = max.toInteger()
    effectNumber = effectNumber.toInteger()
    effectNumber = (effectNumber < 1) ? 1 : ((effectNumber > max) ? max : effectNumber)   
    data = [effect: effectsList[(effectNumber * 2) - 1].trim().replaceAll("}","")]
    executeCommand(ch, "turn_on", data)
}

def componentSetNextEffect(ch) {
    log.warn("setNextEffect not implemented")
}

def componentSetPreviousEffect(ch) {
    log.warn("setPreviousEffect not implemented")
}

def componentSetSpeed(ch, speed) {
    if (logEnable) log.info("received setSpeed request from ${ch.label}, with speed = ${speed}")
    int percentage = 0
    switch (speed) {
        case "on":
            data = [:]
            executeCommand(ch, "turn_on", data)
            break
        case "off":
            data = [:]
            executeCommand(ch, "turn_off", data)
            break
        case "low":
        case "medium-low":
            data = [percentage: "25"]
            executeCommand(ch, "turn_on", data)
            break
        case "auto":
        case "medium":
            data = [percentage: "50"]
            executeCommand(ch, "turn_on", data)
            break
        case "medium-high":
            data = [percentage: "75"]
            executeCommand(ch, "turn_on", data)
            break
        case "high":
            data = [percentage: "100"]
            executeCommand(ch, "turn_on", data)
            break
        default:
            if (logEnable) log.info("No case defined for Fan setSpeed(${speed})")
    }
}

def componentCycleSpeed(ch) {
    def newSpeed = ""
    switch (ch.currentValue("speed")) {
        case "off":
            speed = "low"
            break
        case "low":
        case "medium-low":
            speed = "medium"
            break
        case "medium":
            speed = "medium-high"
            break
        case "medium-high":
            speed = "high"
            break
        case "high":
            speed = "off"
            break
    }
    componentSetSpeed(ch, speed)
}

void componentClose(ch) {
    if (logEnable) log.info("received close request from ${ch.label}")
    service = ch.hasCapability("Valve") ? "close_valve":"close_cover"
    executeCommand(ch, service, [:])
}

void componentOpen(ch) {
    if (logEnable) log.info("received open request from ${ch.label}")
    service = ch.hasCapability("Valve") ? "open_valve":"open_cover"
    executeCommand(ch, service, [:])
}

void componentSetPosition(ch, pos) {
    if (logEnable) log.info("received set position request from ${ch.label}")
    executeCommand(ch, "set_cover_position", [position: pos])
}

void componentSetTiltLevel(ch, tilt) {
    if (logEnable) log.info("received set tilt request from ${ch.label}")
    executeCommand(ch, "set_cover_tilt_position", [position: tilt])
}

void componentStartPositionChange(ch, dir) {
    if(["open", "close"].contains(dir)) {
        if (logEnable) log.info("received ${dir} request from ${ch.label}")
        executeCommand(ch, dir + "_cover", [:])
    }
}

void componentStopPositionChange(ch) {
    if (logEnable) log.info("received stop request from ${ch.label}")
    executeCommand(ch, "stop_cover", [:])
}

void componentLock(ch) {
    if (logEnable) log.info("received lock request from ${ch.label}")
    executeCommand(ch, "lock", [:])
}

void componentUnlock(ch) {
    if (logEnable) log.info("received unlock request from ${ch.label}")
    executeCommand(ch, "unlock", [:])
}

def componentPush(ch, nb) {
    if (logEnable) log.info("received push button ${nb} request from ${ch.label}")
    executeCommand(ch, "press", [:])
}

def componentSetNumber(ch, newValue) {
    if (logEnable) log.info("received set number to ${newValue} request from ${ch.label}")
    newValue = Math.round(newValue / ch.currentValue("step")) * ch.currentValue("step")
    if (newValue < ch.currentValue("minimum")) newValue = ch.currentValue("minimum")
    if (newValue > ch.currentValue("maximum")) newValue = ch.currentValue("maximum")
    executeCommand(ch, "set_value", [value: newValue])
}

def componentRefresh(ch) {
    if (logEnable) log.info("received refresh request from ${ch.label}")
    // special handling since domain is fixed 
    entity = ch.name
    messUpd = JsonOutput.toJson([id: state.id, type: "call_service", domain: "homeassistant", service: "update_entity", service_data: [entity_id: entity]])
    state.id = state.id + 1
    if (logEnable) log.debug("messUpd = ${messUpd}")
    interfaces.webSocket.sendMessage("${messUpd}")
}

def componentSetThermostatMode(ch, thermostatmode) {
    if (logEnable) log.info("received setThermostatMode request from ${ch.label}")
    switch(thermostatmode)
	{
	case "auto":
	    data = [hvac_mode: "heat_cool"]
        break
	case "emergencyHeat":
	    thermostatmode = "heat"
	case "heat":
	case "cool":
	case "off":
	    data =  [hvac_mode: thermostatmode]
	break
	}
    executeCommand(ch, "set_hvac_mode", data)
}

def componentSetCoolingSetpoint(ch, temperature) {
    if (logEnable) log.info("received setCoolingSetpoint request from ${ch.label}")
    if (ch.currentValue("thermostatMode") == "auto") {
        data = [target_temp_high: temperature, target_temp_low: ch.currentValue("heatingSetpoint")]
    }
    else {
	data = [temperature: temperature]
    }
    executeCommand(ch, "set_temperature", data)
}

def componentSetHeatingSetpoint(ch, temperature) {
    if (logEnable) log.info("received setHeatingSetpoint request from ${ch.label}")
    if (ch.currentValue("thermostatMode") == "auto") {
	data = [target_temp_high: ch.currentValue("coolingSetpoint"), target_temp_low: temperature]
    }
    else {
	data = [temperature: temperature] 
    }
    executeCommand(ch, "set_temperature", data)
}

def componentSetThermostatFanMode(ch, fanmode) {
    if (logEnable) log.info("received ${fanmode} request from ${ch.label}")

    if (fanmode == "circulate") {
        executeCommand(ch, "set_hvac_mode", [hvac_mode: "fan_only"])
    }
    else {    
        executeCommand(ch, "set_fan_mode", [fan_mode: fanmode])
    }
}

def componentSetHumidifierMode(ch, modeNumber) {
    if (logEnable) log.info("received set mode request from ${ch.label}")
    def modesList = ch.currentValue("supportedModes")?.tokenize(',=[]')
    def max = modesList.size() / 2
    max = max.toInteger()
    modeNumber = modeNumber.toInteger()
    modeNumber = (modeNumber < 1) ? 1 : ((modeNumber > max) ? max : modeNumber)   
    data = [mode: modesList[(modeNumber * 2) - 1].trim().replaceAll("}","")]
    executeCommand(ch, "set_mode", data)
}

def componentSetHumidity(ch, target) {
    if (logEnable) log.info("received set humidity request from ${ch.label}")
    executeCommand(ch, "set_humidity", target_humidity: target)
}

def componentAuto(ch) {
    componentSetThermostatMode(ch, "auto")
}

def componentCool(ch) {
    componentSetThermostatMode(ch, "cool")
}

def componentEmergencyHeat(ch) {
    componentSetThermostatMode(ch, "emergencyHeat")
}

def componentFanAuto(ch) {
    componentSetThermostatMode(ch, "auto")
}

def componentFanCirculate(ch) {
    componentSetThermostatFanMode(ch, "circulate")
}

def componentFanOn(ch) {
    componentSetThermostatFanMode(ch, "on")
}

def componentHeat(ch) {
    componentSetThermostatMode(ch, "heat")
}

def componentOffTStat(ch) {
    componentSetThermostatMode(ch, "off")
}

def componentStartLevelChange(ch) {
    log.warn("Start level change not supported")
}

def componentStopLevelChange(ch) {
    log.warn("Stop level change not supported")
}

def closeConnection() {
    if (logEnable) log.debug("Closing connection...")   
    state.wasExpectedClose = true
    interfaces.webSocket.close()
}

def callService(entity, service) {
    callService(entity, service, "")
}

def callService(entity, service, data) {
    def cvData = [:]
    cvData = data.tokenize(",").collectEntries{it.tokenize(":").with{[(it[0]):it[1]]}}
    domain = entity?.tokenize(".")[0]
    messUpd = [id: state.id, type: "call_service", domain: domain, service: service, service_data : [entity_id: entity] + cvData]
    state.id = state.id + 1
    messUpdStr = JsonOutput.toJson(messUpd)
    if (logEnable) log.debug("messUpdStr = ${messUpdStr}")
    interfaces.webSocket.sendMessage(messUpdStr)    
}

def executeCommand(ch, service, data) {    
    entity = ch?.name
    domain = entity?.tokenize(".")[0]
    messUpd = [id: state.id, type: "call_service", domain: domain, service: service, service_data : [entity_id: entity] + data]
    state.id = state.id + 1
    messUpdStr = JsonOutput.toJson(messUpd)
    if (logEnable) log.debug("messUpdStr = ${messUpdStr}")
    interfaces.webSocket.sendMessage(messUpdStr)    
}

def deleteAllChildDevices() {
    log.info("Uninstalling all Child Devices")
    getChildDevices().each {
          deleteChildDevice(it.deviceNetworkId)
       }
}
