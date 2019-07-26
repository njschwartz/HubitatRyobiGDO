def version() {"v0.1"}

import hubitat.helper.InterfaceUtils

metadata {
    definition (name: "RyobiGDO", namespace: "njschwartz", author: "Nate Schwartz") {
        capability "Initialize"
        capability "Refresh"
        
        //capability "Actuator"
        capability "Garage Door Control"
		//capability "Contact Sensor"
		capability "Refresh"
		//capability "Sensor"
        //capability "Polling"
  		//capability "Switch" 
        //capability "Light"

    }
}

preferences {
    input("username", "text", title: "Enter username", description: "username", required: true)
    input("password", "text", title: "Enter Password", description: "password", required: true)
    
    //On initalize the garage id's should be found. User must select the proper one (for those of us that have more than one Ryobi GDO) and enter it here.
    input("garageId", "text", title: "Enter garageDoorId", description: "garageId", required: true)
    
    //Api key should now be found automatically
    //input("apiKey", "text", title: "Enter Api Key", description: "apiKey", required: true)
    
    //Turn on and off debugging data in the logs
    input name: "debugOutput", type: "bool", title: "Enable debug logging?", defaultValue: true
}

def parse(response) {
    logDebug("parsed $response")
      
    def json = new groovy.json.JsonSlurper().parseText(response)
    
    if(json.params) {
        if (response.indexOf("parkAssistLaser") != -1) {
            def moduleNum = response.getAt(response.indexOf("parkAssistLaser") + "parkAssistLaser".length() + 1)
            logDebug("Park Assist");
        
            logDebug("${json.params["parkAssistLaser_${moduleNum}.moduleState"].value}")
            state.parkLaser = json.params["parkAssistLaser_${moduleNum}.moduleState"].value
            sendEvent(name: "parkLaser", value: json.params["parkAssistLaser_${moduleNum}.moduleState"].value, displayed: false)
        }
                                                            
        if (response.indexOf("garageDoor") != -1) {
            logDebug("Door Change");
            def moduleNum = response.getAt(response.indexOf("garageDoor") + "garageDoor".length() + 1)
            
            if (json.params["garageDoor_${moduleNum}.doorPosition"]) {
                sendEvent(name: "doorPosition", value: json.params["garageDoor_${moduleNum}.doorPosition"].value, displayed: false)
            }
            
            if (json.params["garageDoor_${moduleNum}.doorState"]) {
               log.info("Door State Change");
        
               def doorState = ""
               def switchState = ""
       
                if (json.params["garageDoor_${moduleNum}.doorState"].value == 1) {
                   doorState = "open"
                   switchState = "on"
                   state.doorState = "open"
               } else if (json.params["garageDoor_${moduleNum}.doorState"].value == 0 ) {
                   doorState = "closed"
                   state.doorState = "closed"
                   switchState = "off"
               } else if (json.params["garageDoor_${moduleNum}.doorState"].value == 3 ) {
                   doorState = "Opening"
                   state.doorState = "Opening"
                   switchState = "on"
               } else if (json.params["garageDoor_${moduleNum}.doorState"].value == 2 ) {
                   doorState = "Closing"
                   state.doorState = "Closing"
                   switchState = "off"
               }
               sendEvent(name: "door", value: doorState, displayed: true)
          }
        }   
   } 
}

def installed() {
    log.info "installed() called"
    initialize()
}

def updated() {
    log.info "updated() called"
    unschedule()
    initialize()
    if (debugOutput) runIn(1800,logsOff)
}

//This will authenticate to the Ryobi service allowing for additional http requests and supply the api key to connect to the websocket
def login() {  
    def jsonMsg = new groovy.json.JsonSlurper().parseText(('{"username":"' + "${username}" + '","password":"' + "${password}" + '"}'))
    def postParams = [
		uri: "https://tti.tiwiconnect.com/api/login",
		requestContentType: 'application/json',
		contentType: 'application/json',
		body : jsonMsg
	]
    
	asynchttpPost('processLoginResponse', postParams)
}

def processLoginResponse(response, data) {
    logDebug("Login Response")
    logDebug("Status of Login call is: ${response.status}")
    if (response.status != 200) {
        logDebug("Login appears to have failed. Please verify your information")
        return
    }
    
    //Get cookie to allow for device requests later 
    state.cookie = response?.headers?.'set-cookie'?.split(";")?.getAt(0)
    state.cookieTime = new Date().time
    
    def responseData = response.getJson()
    logDebug(responseData)
    
    def apiKey = responseData.result.auth.apiKey
    log.info("Your API key is ${apiKey}")
    state.apiKey = apiKey
    
    if(!garageId) {
        getGarageId()
    }
}

def getGarageId() {
    def params = [
		uri: "https://tti.tiwiconnect.com/api/devices",
        contentType: 'application/json',
        headers: ["Content-Type": "application/json",'username':'${username}','password':'${password}', 'Cookie': state.cookie]
    ]
    
    asynchttpGet('processGarageIdResponse', params)
}

def processGarageIdResponse(response, data) {
    logDebug("Got Device request response")
    logDebug("Status of Device request response call is: ${response.status}")
    
    def responseData = response.getJson()
    logDebug(responseData)
    
    for (int i = 0; i < responseData.result.size(); i++) {
        def deviceModel = responseData.result[i].deviceTypeIds[0]
        logDebug( deviceModel)
        if (deviceModel  == 'GD126') {
            log.info( ("The Door ID for a GD126 is: ${responseData.result[i].varName}"))
        } else if (deviceModel == 'GD201') {
            log.info( ("The Door ID for a GD201 is: ${responseData.result[i].varName}"))
        }
    }
}

def initialize() {
    state.version = version()
    log.info "initialize() called"
    
    if (!username || !password) {
        log.warn "Need username and password"
        return
    }
    
   if (!state.apiKey) {
       log.warn "Need Api Key...attempting to login and obtain"
       login()
       return
    }
    
    if (!garageId) {
       log.warn "Need the garageId...attempting to obtain. Once they appear in the logs copy and paste the one you want into the device preferences section."
       getGarageId()
       return
    }
    
    if(!state.apiKey || !username || !password) {
        logDebug("Missing info needed to login. Check preferences to see what else is needed")
    //Connect the webSocket to the Ryobi GDO
    } else {
        try {
            InterfaceUtils.webSocketConnect(device, "wss://tti.tiwiconnect.com/api/wsrpc")
            logDebug(device)
        
            def s = ('{"jsonrpc":"2.0","id":3,"method":"srvWebSocketAuth","params": {"varName": "' + "${username}" + '","apiKey": "' + "${state.apiKey}" + '"}}')
            InterfaceUtils.sendWebSocketMessage(device, s)
           
        }
        catch(e) {
            if (logEnable) log.debug "initialize error: ${e.message}"
            log.error "WebSocket connect failed"
        }
        webSocketStatus()
    }
    getStatus()    
}

def webSocketStatus(String status){
    logDebug( "webSocketStatus- ${status}" )

     if(status != null && status.startsWith('failure: ')) {
         log.warn("failure message from web socket ${status}")
         reconnectWebSocket()
     } else if(status == 'status: open') {
         log.info "websocket is open"
         log.info "success! reset reconnect delay"
         pauseExecution(1000)
         def s = ('{"jsonrpc":"2.0","id":3,"method":"wskSubscribe","params": {"topic": "' + "${garageId}" + '.wskAttributeUpdateNtfy"}}')
         InterfaceUtils.sendWebSocketMessage(device, s)
         state.reconnectDelay = 1
     } else if (status == "status: closing") {
            log.warn "WebSocket connection closing."
    }
}

def reconnectWebSocket() {
    initialize() 
}

//These might possibly be useful if I can get light on/off working in the future
/*
def on() {
    log.info( "Light ON request" )
    def t = ('{"jsonrpc":"2.0","method":"gdoModuleCommand","params":{"msgType":16,"moduleType":5,"portId":7,"moduleMsg":{"lightState":true},"topic":"' + "${garageId}" + '"}}')
    InterfaceUtils.sendWebSocketMessage(device, t)
    sendEvent(name: "switch", value: "on")
}

def off() {
    log.info( "Light OFF request" )
    def t = ('{"jsonrpc":"2.0","method":"gdoModuleCommand","params":{"msgType":16,"moduleType":5,"portId":7,"moduleMsg":{"lightState":false},"topic":"' + "${garageId}" + '"}}')
    InterfaceUtils.sendWebSocketMessage(device, t)
    sendEvent(name: "switch", value: "on")
}
*/


def open() {
    log.info( "Garage Door Open Request" )
    def t = ('{"jsonrpc":"2.0","method":"gdoModuleCommand","params":{"msgType":16,"moduleType":5,"portId":' + "${state.moduleNum}" + ', "moduleMsg":{"doorCommand":1},"topic":"' + "${garageId}" + '"}}')
    InterfaceUtils.sendWebSocketMessage(device, t)
    sendEvent(name: "door", value: "opening")
    runIn(30, getStatus)
}

def close() {
    log.info( "Garage Door Close Request" )
    def t = ('{"jsonrpc":"2.0","method":"gdoModuleCommand","params":{"msgType":16,"moduleType":5,"portId":' + "${state.moduleNum}" + ', "moduleMsg":{"doorCommand":0},"topic":"' + "${garageId}" + '"}}')
    InterfaceUtils.sendWebSocketMessage(device, t)
    sendEvent(name: "door", value: "closing")
    runIn(30, getStatus)
}

//Allow a manual refresh of current garage door status
def refresh() {
    //Check if the cookie is more than 4 hours old. If so relogin and get a new one. (I have no idea how long the cookie is actually good for, I just chose 4hrs arbitrarily)
    def now = new Date().time
    if (state.cookieTime + (1000 * 60 * 60 * 4) < now) {
        login()
        //Wait for login to occur then fetch the latest status
        runIn(3, getStatus)
    //If the cookie isn't old just get the latest status   
    } else {
        getStatus()
    }
}

//Perform a get call to request device status from Ryobi
def getStatus() {
    log.info("Updating Status")
    def params = [
        uri: "https://tti.tiwiconnect.com/api/devices/${garageId}",
        contentType: 'application/json',
        headers: ["Content-Type": "application/json",'username':'${username}','password':'${password}', 'Cookie': state.cookie]
    ]
    asynchttpGet('processGarageStateRequest', params)
}

//There is lots more info in the response that could be pulled out, including info on modules a user may have installed. Might be able to make child devices of those in the future.
def processGarageStateRequest(response, data) {
    logDebug("Got Status request response")
    logDebug("Status of Device request response call is: ${response.status}")

    if (!state.moduleNum) {
        logDebug "Getting the module number of your door"
        def stringData = response.getData()
        def moduleNum = stringData.getAt(stringData.indexOf("garageDoor") + "garageDoor".length() + 1)
        logDebug(moduleNum)
        state.moduleNum = moduleNum
    }
    
    def responseData = response.getJson()
    def currentState = responseData.result[0].deviceTypeMap["garageDoor_${state.moduleNum}"].at.doorState.value
    logDebug "Current garage door state is: ${currentState}"
    //Send appropriate event based on door status
    switch(currentState) {
      case 0:
        sendEvent(name: "door", value: "closed")  
        break;
      case 1:
        sendEvent(name: "door", value: "open")  
        break;
      case 2:
        sendEvent(name: "door", value: "closing") 
        break;
      case 3:
        sendEvent(name: "door", value: "opening")  
        break;
      case 4:
        sendEvent(name: "door", value: "fault")
        break;
    }    
}

//Controls Debug Logs
def logsOff(){
  log.warn "debug logging disabled..."
  device.updateSetting("debugOutput",[value:"false",type:"bool"])
}

private logDebug(msg) {
  if (settings?.debugOutput || settings?.debugOutput == null) {
    log.debug "$msg"
  }
}
