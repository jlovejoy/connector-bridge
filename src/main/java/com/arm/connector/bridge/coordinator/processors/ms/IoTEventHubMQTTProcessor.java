/**
 * @file    IoTEventHubMQTTProcessor.java
 * @brief   MS IoTEventHub MQTT Peer Processor
 * @author  Doug Anson
 * @version 1.0
 * @see
 *
 * Copyright 2015. ARM Ltd. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.arm.connector.bridge.coordinator.processors.ms;

import com.arm.connector.bridge.coordinator.processors.arm.GenericMQTTProcessor;
import com.arm.connector.bridge.coordinator.Orchestrator;
import com.arm.connector.bridge.coordinator.processors.interfaces.PeerInterface;
import com.arm.connector.bridge.core.Utils;
import com.arm.connector.bridge.transport.HttpTransport;
import com.arm.connector.bridge.transport.MQTTTransport;
import com.arm.connector.bridge.core.Transport;
import com.arm.connector.bridge.core.TransportReceiveThread;
import com.arm.connector.bridge.json.JSONParser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fusesource.mqtt.client.QoS;
import org.fusesource.mqtt.client.Topic;

/**
 * IBM IoTEventHub peer processor based on MQTT with MessageSight
 * @author Doug Anson
 */
public class IoTEventHubMQTTProcessor extends GenericMQTTProcessor implements Transport.ReceiveListener, PeerInterface {
    public static int                               NUM_COAP_VERBS = 4;                                   // GET, PUT, POST, DELETE
    public static int                               NUM_COAP_TOPICS = 1;                                  // # of MQTT Topics for CoAP verbs
    
    private String                                  m_iot_event_hub_observe_notification_topic = null;
    private String                                  m_iot_event_hub_coap_cmd_topic_base = null;
    
    private String                                  m_iot_event_hub_name = null;
    private String                                  m_iot_event_hub_password_template = null;
    
    private HashMap<String,Object>                  m_iot_event_hub_endpoints = null;
    private HashMap<String,TransportReceiveThread>  m_mqtt_thread_list = null;
        
    // IoTEventHub Device Manager
    private IoTEventHubDeviceManager                m_iot_event_hub_device_manager = null;
        
    // constructor (singleton)
    public IoTEventHubMQTTProcessor(Orchestrator manager,MQTTTransport mqtt,HttpTransport http) {
        this(manager,mqtt,null,http);
    }
    
    // constructor (with suffix for preferences)
    public IoTEventHubMQTTProcessor(Orchestrator manager,MQTTTransport mqtt,String suffix,HttpTransport http) {
        super(manager,mqtt,suffix,http);
        
        // IoTEventHub Processor Announce
        this.errorLogger().info("MS IoTEventHub Processor ENABLED.");
        
        // initialize the endpoint map
        this.m_iot_event_hub_endpoints = new HashMap<>();
        
        // initialize the listener thread map
        this.m_mqtt_thread_list = new HashMap<>();
                        
        // get our defaults
        this.m_iot_event_hub_name = this.orchestrator().preferences().valueOf("iot_event_hub_name",this.m_suffix);
        this.m_mqtt_host = this.orchestrator().preferences().valueOf("iot_event_hub_mqtt_ip_address",this.m_suffix).replace("__IOT_EVENT_HUB__",this.m_iot_event_hub_name);
                
        // starter kit supports observation notifications
        this.m_iot_event_hub_observe_notification_topic = this.orchestrator().preferences().valueOf("iot_event_hub_observe_notification_topic",this.m_suffix); 
        
        // starter kit can send CoAP commands back through mDS into the endpoint via these Topics... 
        this.m_iot_event_hub_coap_cmd_topic_base = this.orchestrator().preferences().valueOf("iot_event_hub_coap_cmd_topic",this.m_suffix).replace("__COMMAND_TYPE__","#");
                         
        // IoTEventHub Device Manager - will initialize and update our IoTEventHub bindings/metadata
        this.m_iot_event_hub_device_manager = new IoTEventHubDeviceManager(this.orchestrator().errorLogger(),this.orchestrator().preferences(),this.m_suffix,http,this.orchestrator());
                
        // set the MQTT password template
        this.m_iot_event_hub_password_template = this.orchestrator().preferences().valueOf("iot_event_hub_mqtt_password",this.m_suffix).replace("__IOT_EVENT_HUB__",this.m_iot_event_hub_name);
                             
        // initialize our MQTT transport list
        this.initMQTTTransportList();
    }
 
    // we have to override the creation of the authentication hash.. it has to be dependent on a given endpoint name
    @Override
    public String createAuthenticationHash() {
        return Utils.createHash(this.prefValue("iot_event_hub_sas_token",this.m_suffix));
    }
    
    // OVERRIDE: initListener() needs to accomodate a MQTT connection for each endpoint
    @Override
    @SuppressWarnings("empty-statement")
    public void initListener() {
        // do nothing...
        ;
    }
    
    // OVERRIDE: stopListener() needs to accomodate a MQTT connection for each endpoint
    @Override
    @SuppressWarnings("empty-statement")
    public void stopListener() {
        // do nothing...
        ;
    }
    
    // Connection to IoTEventHub MQTT vs. generic MQTT...
    private boolean connect(String ep_name) {
        // if not connected attempt
        if (!this.isConnected(ep_name)) {
            if (this.mqtt(ep_name).connect(this.m_mqtt_host,this.m_mqtt_port,ep_name,this.m_use_clean_session)) {
                this.orchestrator().errorLogger().info("IoTEventHub: Setting CoAP command listener...");
                this.mqtt(ep_name).setOnReceiveListener(this);
                this.orchestrator().errorLogger().info("IoTEventHub: connection completed successfully");
            }
        }
        else {
            // already connected
            this.orchestrator().errorLogger().info("IoTEventHub: Already connected (OK)...");
        }
        
        // return our connection status
        this.orchestrator().errorLogger().info("IoTEventHub: Connection status: " + this.isConnected(ep_name));
        return this.isConnected(ep_name);
    }
    
    // OVERRIDE: process a mDS notification for IoTEventHub
    @Override
    public void processNotification(Map data) {
        // DEBUG
        //this.errorLogger().info("processNotification(IoTEventHub)...");
        
        // get the list of parsed notifications
        List notifications = (List)data.get("notifications");
        for(int i=0;notifications != null && i<notifications.size();++i) {
            // we have to process the payload... this may be dependent on being a string core type... 
            Map notification = (Map)notifications.get(i);
            
            // decode the Payload...
            String b64_coap_payload = (String)notification.get("payload");
            String decoded_coap_payload = Utils.decodeCoAPPayload(b64_coap_payload);
            
            // Try a JSON parse... if it succeeds, assume the payload is a composite JSON value...
            Map json_parsed = this.tryJSONParse(decoded_coap_payload);
            if (json_parsed != null) {
                // add in a JSON object payload value directly... 
                notification.put("value", json_parsed);
            }
            else {
                // add in a decoded payload value as a string type...
                notification.put("value", decoded_coap_payload);
            }
                        
            // we will send the raw CoAP JSON... IoTEventHub can parse that... 
            String coap_raw_json = this.jsonGenerator().generateJson(notification);
            
            // strip off []...
            String coap_json_stripped = this.stripArrayChars(coap_raw_json);
            
            // get our endpoint name
            String ep_name = (String)notification.get("ep");
            
            // encapsulate into a coap/device packet...
            String iot_event_hub_coap_json = coap_json_stripped;
                                    
            // DEBUG
            this.errorLogger().info("IoTEventHub: CoAP notification (STR): " + iot_event_hub_coap_json);
            this.errorLogger().info("IoTEventHub: CoAP notification (JSON): " + notification);
            
            // send to IoTEventHub...
            if (this.mqtt(ep_name) != null) {
                boolean status = this.mqtt(ep_name).sendMessage(this.customizeTopic(this.m_iot_event_hub_observe_notification_topic,ep_name,null),iot_event_hub_coap_json,QoS.AT_MOST_ONCE);           
                if (status == true) {
                    // not connected
                    this.errorLogger().info("IoTEventHub: CoAP notification sent. SUCCESS");
                }
                else {
                    // send failed
                    this.errorLogger().warning("IoTEventHub: CoAP notification not sent. SEND FAILED");
                }
            }
            else {
                // not connected
                this.errorLogger().warning("IoTEventHub: CoAP notification not sent. NOT CONNECTED");
            }
        }
    }
    
    // OVERRIDE: process a re-registration in IoTEventHub
    @Override
    public void processReRegistration(Map data) {
        List notifications = (List)data.get("reg-updates");
        for(int i=0;notifications != null && i<notifications.size();++i) {
            Map entry = (Map)notifications.get(i);
            // DEBUG
            // this.errorLogger().info("IoTEventHub : CoAP re-registration: " + entry);
            if (this.hasSubscriptions((String)entry.get("ep")) == false) {
                // no subscriptions - so process as a new registration
                this.errorLogger().info("IoTEventHub : CoAP re-registration: no subscriptions.. processing as new registration...");
                this.processRegistration(data,"reg-updates");
            }
            else {
                // already subscribed (OK)
                this.errorLogger().info("IoTEventHub : CoAP re-registration: already subscribed (OK)");
            }
        }
    }
    
    // OVERRIDE: handle de-registrations for IoTEventHub
    @Override
    public String[] processDeregistrations(Map parsed) {
        String[] deregistration = super.processDeregistrations(parsed);
        for(int i=0;deregistration != null && i<deregistration.length;++i) {
            // DEBUG
            this.errorLogger().info("IoTEventHub : CoAP de-registration: " + deregistration[i]);
            
            // IoTEventHub add-on... 
            this.unsubscribe(deregistration[i]);
            
            // Remove from IoTEventHub
            this.deregisterDevice(deregistration[i]);
        }
        return deregistration;
    }
    
    // OVERRIDE: process mds registrations-expired messages 
    @Override
    public void processRegistrationsExpired(Map parsed) {
        this.processDeregistrations(parsed);
    }
    
    // OVERRIDE: process a received new registration for IoTEventHub
    @Override
    public void processNewRegistration(Map data) {
        this.processRegistration(data,"registrations");
    }
    
    // OVERRIDE: process a received new registration for IoTEventHub
    @Override
    protected synchronized void processRegistration(Map data,String key) {  
        List endpoints = (List)data.get(key);
        for(int i=0;endpoints != null && i<endpoints.size();++i) {
            Map endpoint = (Map)endpoints.get(i);            
            List resources = (List)endpoint.get("resources");
            for(int j=0;resources != null && j<resources.size();++j) {
                Map resource = (Map)resources.get(j); 
                
                // re-subscribe
                if (this.m_subscriptions.containsSubscription(this.m_mds_domain,(String)endpoint.get("ep"),(String)resource.get("path"))) {
                    // re-subscribe to this resource
                    this.orchestrator().subscribeToEndpointResource((String)endpoint.get("ep"),(String)resource.get("path"),false);
                    
                    // SYNC: here we dont have to worry about Sync options - we simply dispatch the subscription to mDS and setup for it...
                    this.m_subscriptions.removeSubscription(this.m_mds_domain,(String)endpoint.get("ep"),(String)resource.get("path"));
                    this.m_subscriptions.addSubscription(this.m_mds_domain,(String)endpoint.get("ep"),(String)resource.get("path"));
                }
                
                // auto-subscribe
                else if (this.isObservableResource(resource) && this.m_auto_subscribe_to_obs_resources == true) {
                    // auto-subscribe to observable resources... if enabled.
                    this.orchestrator().subscribeToEndpointResource((String)endpoint.get("ep"),(String)resource.get("path"),false);
                    
                    // SYNC: here we dont have to worry about Sync options - we simply dispatch the subscription to mDS and setup for it...
                    this.m_subscriptions.removeSubscription(this.m_mds_domain,(String)endpoint.get("ep"),(String)resource.get("path"));
                    this.m_subscriptions.addSubscription(this.m_mds_domain,(String)endpoint.get("ep"),(String)resource.get("path"));
                }
            }    
            
            // pre-populate the new endpoint with initial values for registration
            this.orchestrator().pullDeviceMetadata(endpoint);
            
            try {
                // create the device in IoTEventHub
                this.errorLogger().info("processRegistration: calling registerNewDevice(): " + endpoint);
                this.registerNewDevice(endpoint);
                this.errorLogger().info("processRegistration: registerNewDevice() completed");
            }
            catch (Exception ex) {
                this.errorLogger().warning("processRegistration: caught exception in registerNewDevice(): " + endpoint,ex); 
            }
            
            try {
                // subscribe for IoTEventHub as well..
                this.errorLogger().info("processRegistration: calling subscribe(): " + endpoint);
                this.subscribe((String)endpoint.get("ep"),(String)endpoint.get("ept"));
                this.errorLogger().info("processRegistration: subscribe() completed");
            }
            catch (Exception ex) {
                this.errorLogger().warning("processRegistration: caught exception in subscribe(): " + endpoint,ex); 
            }
        }
    }
    
    // create the endpoint IoTEventHub topic data
    private HashMap<String,Object> createEndpointTopicData(String ep_name,String ep_type) {
        HashMap<String,Object> topic_data = null;
        if (this.m_iot_event_hub_coap_cmd_topic_base != null) {
            Topic[] list = new Topic[NUM_COAP_TOPICS];
            String[] topic_string_list = new String[NUM_COAP_TOPICS];
            topic_string_list[0] = this.customizeTopic(this.m_iot_event_hub_coap_cmd_topic_base,ep_name,ep_type);
            for(int i=0;i<NUM_COAP_TOPICS;++i) {
                list[i] = new Topic(topic_string_list[i],QoS.AT_LEAST_ONCE);
            }
            topic_data = new HashMap<>();
            topic_data.put("topic_list",list);
            topic_data.put("topic_string_list",topic_string_list);
        }
        return topic_data;
    }
    
    // final customization of a MQTT Topic...
    private String customizeTopic(String topic,String ep_name,String ep_type) {
        String cust_topic = topic.replace("__EPNAME__", ep_name);
        if (ep_type != null) cust_topic = cust_topic.replace("__DEVICE_TYPE__", ep_type);
        this.errorLogger().info("IoTEventHub Customized Topic: " + cust_topic); 
        return cust_topic;
    }
    
    // disconnect
    private void disconnect(String ep_name) {
        if (this.isConnected(ep_name)) {
            this.mqtt(ep_name).disconnect(true);
        }
        this.remove(ep_name);
    }
    
    // are we connected
    private boolean isConnected(String ep_name) {
        if (this.mqtt(ep_name) != null) return this.mqtt(ep_name).isConnected();
        return false;
    }
    
    // subscribe to the IoTEventHub MQTT topics
    private void subscribe_to_topics(String ep_name,Topic topics[]) {
        this.mqtt(ep_name).subscribe(topics);
    }
    
    // does this endpoint already have registered subscriptions?
    private boolean hasSubscriptions(String ep_name) {
        try {
            if (this.m_iot_event_hub_endpoints.get(ep_name) != null) {
                HashMap<String,Object> topic_data = (HashMap<String,Object>)this.m_iot_event_hub_endpoints.get(ep_name);
                if (topic_data != null && topic_data.size() > 0) {
                    return true;
                }
            }
        }
        catch (Exception ex) {
            //silent
        }
        return false;
    }
    
    // register topics for CoAP commands
    private void subscribe(String ep_name,String ep_type) {
        if (ep_name != null && this.validateMQTTConnection(ep_name,ep_type)) {
            // DEBUG
            this.orchestrator().errorLogger().info("IoTEventHub: Subscribing to CoAP command topics for endpoint: " + ep_name);
            try {
                HashMap<String,Object> topic_data = this.createEndpointTopicData(ep_name,ep_type);
                if (topic_data != null) {
                    // get,put,post,delete enablement
                    this.m_iot_event_hub_endpoints.remove(ep_name);
                    this.m_iot_event_hub_endpoints.put(ep_name,topic_data);
                    this.subscribe_to_topics(ep_name,(Topic[])topic_data.get("topic_list"));
                }
                else {
                    this.orchestrator().errorLogger().warning("IoTEventHub: GET/PUT/POST/DELETE topic data NULL. GET/PUT/POST/DELETE disabled");
                }
            }
            catch (Exception ex) {
                this.orchestrator().errorLogger().info("IoTEventHub: Exception in subscribe for " + ep_name + " : " + ex.getMessage());
            }
        }
        else {
            this.orchestrator().errorLogger().info("IoTEventHub: NULL Endpoint name in subscribe()... ignoring...");
        }
    }
    
    // un-register topics for CoAP commands
    private boolean unsubscribe(String ep_name) {
        boolean unsubscribed = false;
        if (ep_name != null && this.mqtt(ep_name) != null) {
            // DEBUG
            this.orchestrator().errorLogger().info("IoTEventHub: Un-Subscribing to CoAP command topics for endpoint: " + ep_name);
            try {
                HashMap<String,Object> topic_data = (HashMap<String,Object>)this.m_iot_event_hub_endpoints.get(ep_name);
                if (topic_data != null) {
                    // unsubscribe...
                    this.mqtt(ep_name).unsubscribe((String[])topic_data.get("topic_string_list")); 
                } 
                else {
                    // not in subscription list (OK)
                    this.orchestrator().errorLogger().info("IoTEventHub: Endpoint: " + ep_name + " not in subscription list (OK).");
                    unsubscribed = true;
                }
            }
            catch (Exception ex) {
                this.orchestrator().errorLogger().info("IoTEventHub: Exception in unsubscribe for " + ep_name + " : " + ex.getMessage());
            }
        }
        else if (this.mqtt(ep_name) != null) {
            this.orchestrator().errorLogger().info("IoTEventHub: NULL Endpoint name... ignoring unsubscribe()...");
            unsubscribed = true;
        }
        else {
            this.orchestrator().errorLogger().info("IoTEventHub: No MQTT connection for " + ep_name + "... ignoring unsubscribe()...");
            unsubscribed = true;
        }  
        
        // clean up
        if (ep_name != null) this.m_iot_event_hub_endpoints.remove(ep_name);
        
        // return the unsubscribe status
        return unsubscribed;
    }
    
    // retrieve a specific element from the topic structure
    private String getTopicElement(String topic,int index) {
        String element = "";
        String[] parsed = topic.split("/");
        if (parsed != null && parsed.length > index) 
            element = parsed[index];
        return element;
    }
    
    // get the endpoint name from the MQTT topic
    private String getEndpointNameFromTopic(String topic) {
        // format: devices/__EPNAME__/messages/devicebound/#
        return this.getTopicElement(topic,1);
    }
    
    // get the resource URI from the message
    private String getCoAPURI(String message) {
        // expected format: { "path":"/303/0/5850", "new_value":"0", "ep":"mbed-eth-observe", "coap_verb": "get"}
        //this.errorLogger().info("getCoAPURI: payload: " + message);
        JSONParser parser = this.orchestrator().getJSONParser();
        Map parsed = parser.parseJson(message);
        return (String)parsed.get("path");
    }
    
    // get the resource value from the message
    private String getCoAPValue(String message) {
        // expected format: { "path":"/303/0/5850", "new_value":"0", "ep":"mbed-eth-observe" , "coap_verb": "get"}
        //this.errorLogger().info("getCoAPValue: payload: " + message);
        JSONParser parser = this.orchestrator().getJSONParser();
        Map parsed = parser.parseJson(message);
        return (String)parsed.get("new_value");
    }
    
    // pull the EndpointName from the message
    private String getCoAPEndpointName(String message) {
        // expected format: { "path":"/303/0/5850", "new_value":"0", "ep":"mbed-eth-observe", "coap_verb": "get" }
        //this.errorLogger().info("getCoAPValue: payload: " + message);
        JSONParser parser = this.orchestrator().getJSONParser();
        Map parsed = parser.parseJson(message);
        return (String)parsed.get("ep");
    }
    
    // pull the CoAP verb from the message
    private String getCoAPVerb(String message) {
        // expected format: { "path":"/303/0/5850", "new_value":"0", "ep":"mbed-eth-observe", "coap_verb": "get" }
        //this.errorLogger().info("getCoAPValue: payload: " + message);
        JSONParser parser = this.orchestrator().getJSONParser();
        Map parsed = parser.parseJson(message);
        return (String)parsed.get("coap_verb");
    }
    
    // CoAP command handler - processes CoAP commands coming over MQTT channel
    @Override
    public void onMessageReceive(String topic, String message) {
        // DEBUG
        this.errorLogger().info("IoTEventHub(CoAP Command): Topic: " + topic + " message: " + message);
        
        // parse the topic to get the endpoint
        // format: devices/__EPNAME__/messages/devicebound/#
        String ep_name = this.getEndpointNameFromTopic(topic);
        
        // pull the CoAP URI and Payload from the message itself... its JSON... 
        // format: { "path":"/303/0/5850", "new_value":"0", "ep":"mbed-eth-observe", "coap_verb": "get" }
        String uri = this.getCoAPURI(message);
        String value = this.getCoAPValue(message);
        
        // pull the CoAP verb from the message itself... its JSON...
        // format: { "path":"/303/0/5850", "new_value":"0", "ep":"mbed-eth-observe", "coap_verb": "get" }
        String coap_verb = this.getCoAPVerb(message);
        
        // if the ep_name is wildcarded... get the endpoint name from the JSON payload
        // format: { "path":"/303/0/5850", "new_value":"0", "ep":"mbed-eth-observe", "coap_verb": "get" }
        if (ep_name == null || ep_name.length() <= 0 || ep_name.equalsIgnoreCase("+")) {
            ep_name = this.getCoAPEndpointName(message);
        }
        
        // dispatch the coap resource operation request
        String response = this.orchestrator().processEndpointResourceOperation(coap_verb,ep_name,uri,value);
        
        // examine the response
        if (response != null && response.length() > 0) {
            // SYNC: We only process AsyncResponses from GET verbs... we dont sent HTTP status back through IoTEventHub.
            this.errorLogger().info("IoTEventHub(CoAP Command): Response: " + response);
            
            // AsyncResponse detection and recording...
            if (this.isAsyncResponse(response) == true) {
                if (coap_verb.equalsIgnoreCase("get") == true) {
                    // its an AsyncResponse.. so record it...
                    this.recordAsyncResponse(response,coap_verb,this.mqtt(ep_name),this,topic,message,uri,ep_name);
                }
                else {
                    // we ignore AsyncResponses to PUT,POST,DELETE
                    this.errorLogger().info("IoTEventHub(CoAP Command): Ignoring AsyncResponse for " + coap_verb + " (OK).");
                }
            }
            else if (coap_verb.equalsIgnoreCase("get")) {
                // not an AsyncResponse... so just emit it immediately... only for GET...
                this.errorLogger().info("IoTEventHub(CoAP Command): Response: " + response + " from GET... creating observation...");
                
                // we have to format as an observation...
                String observation = this.createObservation(coap_verb,ep_name,uri,response);
                
                // DEBUG
                this.errorLogger().info("IoTEventHub(CoAP Command): Sending Observation(GET): " + observation);
                
                // send the observation (GET reply)...
                if (this.mqtt(ep_name) != null) {
                    boolean status = this.mqtt(ep_name).sendMessage(this.customizeTopic(this.m_iot_event_hub_observe_notification_topic,ep_name,null),observation,QoS.AT_MOST_ONCE); 
                    if (status == true) {
                        // not connected
                        this.errorLogger().info("IoTEventHub(CoAP Command): CoAP observation(get) sent. SUCCESS");
                    }
                    else {
                        // send failed
                        this.errorLogger().warning("IoTEventHub(CoAP Command): CoAP observation(get) not sent. SEND FAILED");
                    }
                }
                else {
                    // not connected
                    this.errorLogger().warning("IoTEventHub(CoAP Command): CoAP observation(get) not sent. NOT CONNECTED");
                }
            }
        }
    }
    
    // create an observation JSON as a response to a GET request...
    private String createObservation(String verb, String ep_name, String uri, String value) {
        Map notification = new HashMap<>();
        
        // needs to look like this:  {"d":{"path":"/303/0/5700","payload":"MjkuNzU\u003d","max-age":"60","ep":"350e67be-9270-406b-8802-dd5e5f20ansond","value":"29.75"}}    
        notification.put("value", value);
        notification.put("path", uri);
        notification.put("ep",ep_name);
        
        // add a new field to denote its a GET
        notification.put("verb",verb);

        // we will send the raw CoAP JSON... IoTEventHub can parse that... 
        String coap_raw_json = this.jsonGenerator().generateJson(notification);

        // strip off []...
        String coap_json_stripped = this.stripArrayChars(coap_raw_json);

        // encapsulate into a coap/device packet...
        // XXX
        String iot_event_hub_coap_json = null;

        // DEBUG
        this.errorLogger().info("IoTEventHub: CoAP notification(GET REPLY): " + iot_event_hub_coap_json);
        
        // return the IoTEventHub-specific observation JSON...
        return iot_event_hub_coap_json;
    }
    
    // default formatter for AsyncResponse replies
    @Override
    public String formatAsyncResponseAsReply(Map async_response,String verb) {
        if (verb != null && verb.equalsIgnoreCase("GET") == true) {           
            try {
                // DEBUG
                this.errorLogger().info("IoTEventHub: CoAP AsyncResponse for GET: " + async_response);
                
                // get the Map of the response
                Map response_map = (Map)async_response.get("response_map");
                
                // Convert back to String, then to List
                String t = this.orchestrator().getJSONGenerator().generateJson(response_map);
                List async_responses = (List)this.orchestrator().getJSONParser().parseJson(t);
                for(int i=0;async_responses != null && i<async_responses.size();++i) {
                    // get the ith entry from the list
                    Map response = (Map)async_responses.get(i);
                    
                    // DEBUG
                    this.errorLogger().info("IoTEventHub: CoAP response(" + i + "): " + response);
                    
                    // get the payload from the ith entry
                    String payload = (String)response.get("payload");
                    if (payload != null) {
                        // trim 
                        payload = payload.trim();
                        
                        // parse if present
                        if (payload.length() > 0) {
                            // Base64 decode
                            String value = Utils.decodeCoAPPayload(payload);
                            
                            // build out the response
                            String uri = (String)async_response.get("uri");
                            String ep_name = (String)async_response.get("ep_name");
                            
                            // build out the 
                            String message = this.createObservation(verb, ep_name, uri, value);
                            
                            // DEBUG
                            this.errorLogger().info("IoTEventHub: Created(" + verb + ") GET Observation: " + message);
                            
                            // return the message
                            return message;
                        }
                    }
                }
            }
            catch (Exception ex) {
                // Error in creating the observation message from the AsyncResponse GET reply... 
                this.errorLogger().warning("formatAsyncResponseAsReply(IoTEventHub): Exception during GET reply -> observation creation. Not sending GET as observation...",ex);
            }
        }
        return null;
    }
    
    // validate the MQTT Connection
    private synchronized boolean validateMQTTConnection(String ep_name,String ep_type) {        
        // see if we already have a connection for this endpoint...
        if (this.mqtt(ep_name) == null) {
            // create a MQTT connection for this endpoint... 
            this.createAndStartMQTTForEndpoint(ep_name,ep_type);
        }
        
        // return our connection status
        return this.isConnected(ep_name);
    }
    
    // process new device registration
    @Override
    protected synchronized Boolean registerNewDevice(Map message) {
        if (this.m_iot_event_hub_device_manager != null) {
            // create the device in IoTEventHub
            Boolean success = this.m_iot_event_hub_device_manager.registerNewDevice(message);
            
            // if successful, validate (i.e. add...) an MQTT Connection
            if (success == true) this.validateMQTTConnection((String)message.get("ep"),(String)message.get("ept"));
            
            // return status
            return success;
        }
        return false;
    }
    
    // process device de-registration
    @Override
    protected synchronized Boolean deregisterDevice(String device) {
        if (this.m_iot_event_hub_device_manager != null) {
            // DEBUG
            this.errorLogger().info("deregisterDevice(IoTEventHub): deregistering device: " + device);
            
            // disconnect, remove the threaded listener... 
            if (this.m_mqtt_thread_list.get(device) != null) {
                try {
                    this.m_mqtt_thread_list.get(device).disconnect();
                } 
                catch (Exception ex) {
                    // note but continue...
                    this.errorLogger().warning("deregisterDevice(IoTEventHub): exception during deregistration",ex);
                }
                this.m_mqtt_thread_list.remove(device);
            }
            
            // also remove MQTT Transport instance too...
            this.disconnect(device);
            
            // remove the device from IoTEventHub
            if (this.m_iot_event_hub_device_manager.deregisterDevice(device) == false) {
                this.errorLogger().warning("deregisterDevice(IoTEventHub): unable to de-register device from IoTEventHub...");
            }
        }
        return true;
    }
    
    // add a MQTT transport for a given endpoint - this is how MS IoTEventHub MQTT integration works... 
    private synchronized void createAndStartMQTTForEndpoint(String ep_name,String ep_type) {
        if (this.mqtt(ep_name) == null) {
            // create a new MQTT Transport instance
            MQTTTransport mqtt = new MQTTTransport(this.errorLogger(),this.preferences());

            // MQTT username is based upon the device ID (endpoint_name)
            String username = this.orchestrator().preferences().valueOf("iot_event_hub_mqtt_username",this.m_suffix).replace("__IOT_EVENT_HUB__",this.m_iot_event_hub_name).replace("__EPNAME__",ep_name);

            // set the creds for this MQTT Transport instance
            mqtt.setClientID(ep_name);
            mqtt.setUsername(username);
            mqtt.setPassword(this.m_iot_event_hub_device_manager.createMQTTPassword(ep_name));
            
            // IoTEventHub only works with SSL... so force it
            mqtt.forceSSLUsage(true);

            // add it to the list indexed by the endpoint name... not the clientID...
            this.addMQTTTransport(ep_name,mqtt);

            // DEBUG
            this.errorLogger().info("IoTEventHub: connecting to MQTT for endpoint: " + ep_name + " type: " + ep_type + "...");

            // connect and start listening... 
            if (this.connect(ep_name) == true) {
                // DEBUG
                this.errorLogger().info("IoTEventHub: connected to MQTT. Creating and registering listener Thread for endpoint: " + ep_name + " type: " + ep_type);

                // ensure we only have 1 thread/endpoint
                if (this.m_mqtt_thread_list.get(ep_name) != null) {
                    TransportReceiveThread listener = (TransportReceiveThread)this.m_mqtt_thread_list.get(ep_name);
                    listener.disconnect();
                    this.m_mqtt_thread_list.remove(ep_name);
                }
                
                // create and start the listener
                TransportReceiveThread listener = new TransportReceiveThread(mqtt);
                listener.setOnReceiveListener(this);
                this.m_mqtt_thread_list.put(ep_name,listener);
                listener.start();
            } 
            else {
                // unable to connect!
                this.errorLogger().critical("IoTEventHub: Unable to connect to MQTT for endpoint: " + ep_name + " type: " + ep_type);
                this.remove(ep_name);
                
                // ensure we only have 1 thread/endpoint
                if (this.m_mqtt_thread_list.get(ep_name) != null) {
                    TransportReceiveThread listener = (TransportReceiveThread)this.m_mqtt_thread_list.get(ep_name);
                    listener.disconnect();
                    this.m_mqtt_thread_list.remove(ep_name);
                }
            }
        }
        else {
            // already connected... just ignore
            this.errorLogger().info("IoTEventHub: already have connection for " + ep_name + " (OK)");
        }
    }
}
