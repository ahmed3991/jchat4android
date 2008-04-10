package com.tilab.msn;

import jade.core.AID;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.Property;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.util.Logger;
import jade.wrapper.gateway.GatewayAgent;
import android.location.Location;


public class MsnAgent extends GatewayAgent {

	public static final String msnDescName = "android-msn-service";
	public static final String msnDescType = "android-msn";

	public static final String PROPERTY_NAME_LOCATION_LAT="Latitude";
	public static final String PROPERTY_NAME_LOCATION_LONG="Longitude";
	public static final String PROPERTY_NAME_LOCATION_ALT="Altitude";

	public static final String CHAT_ONTOLOGY= "chat_ontology";

	private DFAgentDescription myDescription;
	private ACLMessage subscriptionMessage;
	private ContactsUpdaterBehaviour contactsUpdaterB;
	private MessageReceiverBehaviour messageRecvB;


	private final Logger myLogger = Logger.getMyLogger(this.getClass().getName());

	//In this method we shall register to df and subscribe
	protected void setup() {
		super.setup();

		myDescription = new DFAgentDescription();
		//fill a msn service description
		ServiceDescription msnServiceDescription = new ServiceDescription();
		msnServiceDescription.setName(msnDescName);
		msnServiceDescription.setType(msnDescType);
		myDescription.addServices(msnServiceDescription);

		//subscribe to DF
		subscriptionMessage = DFService.createSubscriptionMessage(this, this.getDefaultDF(), myDescription, null);

		Location curLoc = ContactManager.getInstance().getMyContact().getLocation();

		Property p = new Property(PROPERTY_NAME_LOCATION_LAT,new Double(curLoc.getLatitude()));
		msnServiceDescription.addProperties(p);
		p = new Property(PROPERTY_NAME_LOCATION_LONG,new Double(curLoc.getLongitude()));
		msnServiceDescription.addProperties(p);
		p= new Property(PROPERTY_NAME_LOCATION_ALT,new Double(curLoc.getAltitude()));
		msnServiceDescription.addProperties(p);		
		myDescription.setName(this.getAID());

		try {
			myLogger.log(Logger.INFO, "Registering to DF!");
			DFService.register(this, myDescription);
		} catch (FIPAException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		//added behaviour to dispatch chat messages
		messageRecvB = new MessageReceiverBehaviour(); 
		addBehaviour(messageRecvB);
		String[] args = (String[])getArguments();
		myLogger.log(Logger.INFO, "UPDATE TIME: " + args[0]);
		contactsUpdaterB = new ContactsUpdaterBehaviour(Long.parseLong(args[0]));
		addBehaviour(contactsUpdaterB);
	}

	public ACLMessage getSubscriptionMessage(){
		return subscriptionMessage;
	}

	public DFAgentDescription getAgentDescription(){
		return myDescription;
	}



	//Here we will deregister and unsubscribe
	protected void takeDown() {
		myLogger.log(Logger.INFO, "Starting agent takeDown() ");

		ACLMessage unsubcribeMsg = DFService.createCancelMessage(this, getDefaultDF(), getSubscriptionMessage());
		send(unsubcribeMsg);

		myLogger.log(Logger.INFO, "DS Subscription Canceling message was sent!");

		try {
			DFService.deregister(this, myDescription);
			myLogger.log(Logger.INFO, "Deregistering from DF!");
		} catch (FIPAException e) {
			// TODO Auto-generated catch block
			e.printStackTrace(); 
		}	
	}

	//used to pass data to agent
	protected void processCommand(final Object command) {

		if (command instanceof SynchCommandBehaviour) {
			addBehaviour( (Behaviour) command);
		}else if (command instanceof Behaviour){
			addBehaviour( (Behaviour) command);
			releaseCommand(command);
		}else if(command instanceof ContactsUIUpdater){
			contactsUpdaterB.setContactsUpdater((ContactsUIUpdater)command);
			releaseCommand(command);
		} 
	}

	private class MessageReceiverBehaviour extends CyclicBehaviour{



		public void action() {

			try {
				MessageTemplate mt = MessageTemplate.MatchOntology(CHAT_ONTOLOGY);
				ACLMessage msg = myAgent.receive(mt);
				//If a message is received
				if(msg != null){
					myLogger.log(Logger.INFO, msg.toString());

					//retrieve the session id
					String sessionId = msg.getConversationId();
					myLogger.log(Logger.INFO, "Received Message... session ID is " + sessionId);

					//check if there's an activity to update
					ContactsUIUpdater updater = MsnSessionManager.getInstance().retrieveMsgReceivedUpdater(sessionId);
					Contact sender = ContactManager.getInstance().getContactByAgentId(msg.getSender().getLocalName());
					MsnSessionMessage sessionMessage = new MsnSessionMessage(msg.getContent(),sender.getName(),true);

					//If we have no activity we need to add a notification
					if (updater == null) {

						myLogger.log(Logger.INFO, "Updater not found... creating a new session object");

						//Before adding the notification w have to add a new session
						MsnSession session = MsnSessionManager.getInstance().createNewMsnSession(sessionId);
						//Add all participants

						Contact myContact = ContactManager.getInstance().getMyContact();
						myLogger.log(Logger.INFO, "Adding sender " + sender.getName() + "as a session participant");
						session.addParticipant(sender);
						for (jade.util.leap.Iterator it = msg.getAllReceiver(); it.hasNext();) {
							AID agentId = (AID) it.next();

							if (!agentId.getName().startsWith(myContact.getAgentContact())){
								Contact otherContact = ContactManager.getInstance().getContactByAgentId(agentId.getLocalName());
								myLogger.log(Logger.INFO, "Adding contact " + otherContact.getName() + "as a session participant");
								session.addParticipant(otherContact);
							}
						}

						session.addMessage(sessionMessage);
						IncomingNotificationUpdater notificationUpdater = MsnSessionManager.getInstance().getNotificationUpdater();
						myLogger.log(Logger.INFO, "Calling notification updater to add a new notification on status bar");
						notificationUpdater.postMessageNotification(msg);
					} else {
						myLogger.log(Logger.INFO, "Updater found... retrieving existing session");
						MsnSession session = MsnSessionManager.getInstance().retrieveSession(sessionId);
						session.addMessage(sessionMessage);
						myLogger.log(Logger.INFO, "Posting UI update on the retrieved updater");
						updater.postUIUpdate(sessionMessage);
					}

				}else{
					block();
				}				
			}
			catch(Throwable t) {
				myLogger.log(Logger.SEVERE,"***  Uncaught Exception for agent " + myAgent.getLocalName() + "  ***",t);
			}

		}
	}


}

