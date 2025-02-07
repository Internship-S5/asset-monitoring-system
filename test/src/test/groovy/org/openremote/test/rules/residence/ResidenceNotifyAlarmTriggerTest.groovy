package org.openremote.test.rules.residence


import com.google.firebase.messaging.Message
import net.fortuna.ical4j.model.Date
import net.fortuna.ical4j.model.Recur
import net.fortuna.ical4j.model.parameter.Value
import org.openremote.agent.protocol.simulator.SimulatorProtocol
import org.openremote.manager.agent.AgentService
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.notification.NotificationService
import org.openremote.manager.notification.PushNotificationHandler
import org.openremote.manager.rules.RulesEngine
import org.openremote.manager.rules.RulesService
import org.openremote.manager.rules.RulesetDeployment
import org.openremote.manager.rules.RulesetStorageService
import org.openremote.manager.rules.geofence.ORConsoleGeofenceAssetAdapter
import org.openremote.manager.setup.SetupService
import org.openremote.model.calendar.CalendarEvent
import org.openremote.model.rules.RulesetStatus
import org.openremote.setup.integration.ManagerTestSetup
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.attribute.AttributeRef
import org.openremote.model.console.ConsoleProvider
import org.openremote.model.console.ConsoleRegistration
import org.openremote.model.console.ConsoleResource
import org.openremote.model.notification.AbstractNotificationMessage
import org.openremote.model.notification.Notification
import org.openremote.model.notification.NotificationSendResult
import org.openremote.model.notification.PushNotificationMessage
import org.openremote.model.rules.AssetRuleset
import org.openremote.model.rules.Ruleset
import org.openremote.model.rules.TemporaryFact
import com.fasterxml.jackson.databind.node.ObjectNode
import org.openremote.model.util.ValueUtil
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

import static org.openremote.setup.integration.ManagerTestSetup.DEMO_RULE_STATES_APARTMENT_1
import static org.openremote.model.Constants.KEYCLOAK_CLIENT_ID
import static org.openremote.model.asset.AssetResource.Util.WRITE_ATTRIBUTE_HTTP_METHOD
import static org.openremote.model.asset.AssetResource.Util.getWriteAttributeUrl
import static org.openremote.model.util.ValueUtil.parse

class ResidenceNotifyAlarmTriggerTest extends Specification implements ManagerContainerTrait {

    def "Trigger notification when presence is detected and alarm enabled"() {

        def notificationIds = []
        def targetTypes = []
        def targetIds = []
        def messages = []

        given: "the container environment is started with the mock handler"
        def conditions = new PollingConditions(timeout: 20, delay: 0.2)
        def container = startContainer(defaultConfig(), defaultServices())
        def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
        def rulesService = container.getService(RulesService.class)
        def notificationService = container.getService(NotificationService.class)
        def pushNotificationHandler = container.getService(PushNotificationHandler.class)
        def assetProcessingService = container.getService(AssetProcessingService.class)
        def agentService = container.getService(AgentService.class)
        def assetStorageService = container.getService(AssetStorageService.class)
        def rulesetStorageService = container.getService(RulesetStorageService.class)

        and: "a mock push notification handler is injected"
        PushNotificationHandler mockPushNotificationHandler = Spy(pushNotificationHandler)
        mockPushNotificationHandler.isValid() >> true
        mockPushNotificationHandler.sendMessage(_ as Long, _ as Notification.Source, _ as String, _ as Notification.Target, _ as AbstractNotificationMessage) >> {
            id, source, sourceId, target, message ->
                notificationIds << id
                targetTypes << target.type
                targetIds << target.id
                messages << message
                callRealMethod()
        }
        // Assume sent to FCM
        mockPushNotificationHandler.sendMessage(_ as Message) >> {
            message -> return NotificationSendResult.success()
        }
        notificationService.notificationHandlerMap.put(pushNotificationHandler.getTypeName(), mockPushNotificationHandler)

        RulesEngine apartment1Engine

        and: "some rules"
        Ruleset ruleset = new AssetRuleset(
            managerTestSetup.apartment1Id,
            "Demo Apartment - Notify Alarm Trigger",
            Ruleset.Lang.GROOVY, getClass().getResource("/org/openremote/test/rules/ResidenceNotifyAlarmTrigger.groovy").text)
        ruleset = rulesetStorageService.merge(ruleset)

        expect: "the rule engines to become available and be running"
        conditions.eventually {
            apartment1Engine = rulesService.assetEngines.get(managerTestSetup.apartment1Id)
            assert apartment1Engine != null
            assert apartment1Engine.isRunning()
            assert apartment1Engine.assetStates.size() == DEMO_RULE_STATES_APARTMENT_1
        }

        and: "the alarm enabled, presence detected flag of room should not be set"
        def apartment1Asset = assetStorageService.find(managerTestSetup.apartment1Id, true)
        assert !apartment1Asset.getAttribute("alarmEnabled").get().value.orElse(null)
        def livingRoomAsset = assetStorageService.find(managerTestSetup.apartment1LivingroomId, true)
        assert !livingRoomAsset.getAttribute("presenceDetected").orElse(null).value.orElse(null)

        and: "an authenticated test user"
        def realm = "building"
        def accessToken = authenticate(
                container,
                realm,
                KEYCLOAK_CLIENT_ID,
                "testuser3",
                "testuser3"
        ).token

        and: "the notification and console resources"
        def authenticatedConsoleResource = getClientApiTarget(serverUri(serverPort), realm, accessToken).proxy(ConsoleResource.class)

        when: "a console is registered by the test user"
        def consoleRegistration = new ConsoleRegistration(null,
                "Test Console",
                "1.0",
                "Android 7.0",
                new HashMap<String, ConsoleProvider>() {
                    {
                        put("geofence", new ConsoleProvider(
                                ORConsoleGeofenceAssetAdapter.NAME,
                                true,
                                false,
                                false,
                                false,
                                false,
                                null
                        ))
                        put("push", new ConsoleProvider(
                                "fcm",
                                true,
                                true,
                                true,
                                true,
                                false,
                                ((ObjectNode) parse("{\"token\": \"23123213ad2313b0897efd\"}").orElse(null)
                        )))
                    }
                },
                "",
                ["manager"] as String[])
        def returnedConsoleRegistration = authenticatedConsoleResource.register(null, consoleRegistration)

        then: "the console should be registered"
        assert returnedConsoleRegistration.id != null

        when: "the alarm is enabled"
        assetProcessingService.sendAttributeEvent(new AttributeEvent(
                managerTestSetup.apartment1Id, "alarmEnabled", true
        ))

        then: "that value should be stored"
        conditions.eventually {
            def asset = assetStorageService.find(managerTestSetup.apartment1Id, true)
            assert asset.getAttribute("alarmEnabled", Boolean.class).get().getValue().orElse(false)
        }

        when: "the presence is detected in Living room of apartment 1"
        ((SimulatorProtocol)agentService.getProtocolInstance(managerTestSetup.apartment1ServiceAgentId)).updateSensor(
            new AttributeEvent(managerTestSetup.apartment1LivingroomId, "presenceDetected", true)
        )

        then: "that value should be stored"
        conditions.eventually {
            def asset = assetStorageService.find(managerTestSetup.apartment1LivingroomId, true)
            assert asset.getAttribute("presenceDetected").get().value.orElse(null)
        }

        and: "the user should be notified"
        conditions.eventually {
            assert notificationIds.size() == 1
            assert targetTypes[0] == Notification.TargetType.ASSET
            assert targetIds[0] == returnedConsoleRegistration.id
            def pushMessage = (PushNotificationMessage) messages[0]
            assert pushMessage.title == "Apartment Alarm"
            assert pushMessage.body.startsWith("Aanwezigheid in Living Room")
            assert pushMessage.buttons.size() == 2
            assert pushMessage.buttons[0].title == "Details"
            assert pushMessage.buttons[0].action.url == "#security"
            assert pushMessage.buttons[0].action.httpMethod == null
            assert pushMessage.buttons[0].action.data == null
            assert !pushMessage.buttons[0].action.openInBrowser
            assert !pushMessage.buttons[0].action.silent
            assert pushMessage.buttons[1].title == "Alarm uit"
            assert pushMessage.buttons[1].action.url == getWriteAttributeUrl(new AttributeRef(managerTestSetup.apartment1Id, "alarmEnabled"))
            assert pushMessage.buttons[1].action.httpMethod == WRITE_ATTRIBUTE_HTTP_METHOD
            assert !ValueUtil.getBoolean(pushMessage.buttons[1].action.data).orElse(true)
            assert !pushMessage.buttons[1].action.openInBrowser
            assert pushMessage.buttons[1].action.silent
        }

        when: "time moves on and other events happen that trigger evaluation in the rule engine"
        advancePseudoClock(20, TimeUnit.MINUTES, container)
        ((SimulatorProtocol)agentService.getProtocolInstance(managerTestSetup.apartment1ServiceAgentId)).updateSensor(
            new AttributeEvent(managerTestSetup.apartment1LivingroomId, "co2Level", 444)
        )

        then: "still only one notification should have been sent"
        conditions.eventually {
            def asset = assetStorageService.find(managerTestSetup.apartment1LivingroomId, true)
            assert asset.getAttribute("co2Level").get().value.orElse(null) == 444
            assert notificationIds.size() == 1
        }

        when: "time moves on more than the alarm silence duration"
        advancePseudoClock(30, TimeUnit.MINUTES, container)

        then: "another notification should be sent"
        conditions.eventually {
            assert notificationIds.size() == 2
        }

        when: "the presence is no longer triggered in Living room of apartment 1"
        advancePseudoClock(5, TimeUnit.SECONDS, container)
        ((SimulatorProtocol)agentService.getProtocolInstance(managerTestSetup.apartment1ServiceAgentId)).updateSensor(
            new AttributeEvent(managerTestSetup.apartment1LivingroomId, "presenceDetected", false)
        )

        then: "that value should be stored"
        conditions.eventually {
            def asset = assetStorageService.find(managerTestSetup.apartment1LivingroomId, true)
            assert !asset.getAttribute("presenceDetected").get().value.orElse(null)
        }

        when: "time moves on"
        advancePseudoClock(40, TimeUnit.MINUTES, container)

        then: "still only two notifications should have been sent"
        conditions.eventually {
            assert notificationIds.size() == 2
        }

        when: "a validity period is added to the ruleset that enables the ruleset for the next day of the week repeating for 2 weeks"
        stopPseudoClock()
        def tomorrowMidnight = Instant.ofEpochMilli(getClockTimeOf(container)).truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS)
        def from = tomorrowMidnight.plus(6, ChronoUnit.HOURS)
        def to = tomorrowMidnight.plus(8, ChronoUnit.HOURS)
        def recur = new Recur(Recur.WEEKLY, 2)
        def validity = new CalendarEvent(from.atZone(ZoneOffset.UTC).toDate(), to.atZone(ZoneOffset.UTC).toDate(), recur)
        ruleset.setValidity(validity)
        rulesetStorageService.merge(ruleset)
        ruleset = rulesetStorageService.find(AssetRuleset.class, ruleset.id)

        then: "the ruleset should have saved successfully and the validity should also have been stored"
        assert ruleset != null
        assert ruleset.getValidity() != null
        assert ruleset.getValidity().getNextOrActiveFromTo(new Date(getClockTimeOf(container))).key == from.toEpochMilli()
        assert ruleset.getValidity().getNextOrActiveFromTo(new Date(getClockTimeOf(container))).value == to.toEpochMilli()
        assert ruleset.getValidity().getRecurrence() != null
        def recurrenceList = ruleset.getValidity().getRecurrence().getDates(new net.fortuna.ical4j.model.Date(getClockTimeOf(container)), new net.fortuna.ical4j.model.Period(new net.fortuna.ical4j.model.DateTime(new Date(getClockTimeOf(container))), new net.fortuna.ical4j.model.DateTime(tomorrowMidnight.plus(30, ChronoUnit.DAYS).toDate())), Value.DATE)
        assert recurrenceList.size() == 2

        and: "the ruleset state should be deployed and paused"
        RulesetDeployment rulesetDeployment
        conditions.eventually {
            apartment1Engine = rulesService.assetEngines.get(managerTestSetup.apartment1Id)
            assert apartment1Engine != null
            assert apartment1Engine.isRunning()
            assert apartment1Engine.deployments.size() == 1
            rulesetDeployment = apartment1Engine.deployments.get(ruleset.getId())
            assert rulesetDeployment != null
            assert rulesetDeployment.status == RulesetStatus.PAUSED
            assert apartment1Engine.pauseTimers.size() == 0
            assert apartment1Engine.unpauseTimers.size() == 1
            // Assert scheduled future delay is correct
            assert from.toEpochMilli() - getClockTimeOf(container) - apartment1Engine.unpauseTimers.get(ruleset.getId()).getDelay(TimeUnit.MILLISECONDS) <= 10000
        }

        when: "time advances to within the first valid time period"
        advancePseudoClock((from.toEpochMilli()-getClockTimeOf(container))+10000, TimeUnit.MILLISECONDS, container)
        apartment1Engine.unPauseRuleset(rulesetDeployment)

        then: "the ruleset status should be un-paused"
        conditions.eventually {
            assert rulesetDeployment.status == RulesetStatus.DEPLOYED
        }

        and: "the next pause of the ruleset should be scheduled"
        assert apartment1Engine.pauseTimers.size() == 1
        assert apartment1Engine.unpauseTimers.size() == 0
        // Assert scheduled future delay is correct
        assert to.toEpochMilli() - getClockTimeOf(container) - apartment1Engine.pauseTimers.get(ruleset.getId()).getDelay(TimeUnit.MILLISECONDS) <= 10000

        when: "time advances past the first expiry the ruleset is paused again"
        advancePseudoClock((to.toEpochMilli()-getClockTimeOf(container))+10000, TimeUnit.MILLISECONDS, container)
        apartment1Engine.pauseRuleset(rulesetDeployment)

        then: "the ruleset status should be paused"
        conditions.eventually {
            assert rulesetDeployment.status == RulesetStatus.PAUSED
        }

        and: "the next unpause of the ruleset should be scheduled"
        assert apartment1Engine.pauseTimers.size() == 0
        assert apartment1Engine.unpauseTimers.size() == 1
        // Assert scheduled future delay is correct
        assert from.plus(7, ChronoUnit.DAYS).toEpochMilli() - getClockTimeOf(container) - apartment1Engine.unpauseTimers.get(ruleset.getId()).getDelay(TimeUnit.MILLISECONDS) <= 10000

        when: "time advances to within the next valid time period"
        advancePseudoClock((from.plus(7, ChronoUnit.DAYS).plus(61, ChronoUnit.MINUTES).toEpochMilli() - getClockTimeOf(container)), TimeUnit.MILLISECONDS, container)
        apartment1Engine.unPauseRuleset(rulesetDeployment)

        then: "the ruleset status should be un-paused"
        conditions.eventually {
            assert rulesetDeployment.status == RulesetStatus.DEPLOYED
        }

        and: "the next pause of the ruleset should be scheduled"
        assert apartment1Engine.pauseTimers.size() == 1
        assert apartment1Engine.unpauseTimers.size() == 0
        // Assert scheduled future delay is correct
        assert to.plus(7, ChronoUnit.DAYS).toEpochMilli() - getClockTimeOf(container) - apartment1Engine.pauseTimers.get(ruleset.getId()).getDelay(TimeUnit.MILLISECONDS) <= 10000

        when: "time advances past the second expiry the ruleset is paused again"
        advancePseudoClock((to.plus(7, ChronoUnit.DAYS).plus(61, ChronoUnit.MINUTES).toEpochMilli()-getClockTimeOf(container)), TimeUnit.MILLISECONDS, container)
        apartment1Engine.pauseRuleset(rulesetDeployment)

        then: "the ruleset status should be expired"
        conditions.eventually {
            assert rulesetDeployment.status == RulesetStatus.EXPIRED
            assert apartment1Engine.pauseTimers.size() == 0
            assert apartment1Engine.unpauseTimers.size() == 0
        }

        cleanup: "the mock is removed"
        notificationService.notificationHandlerMap.put(pushNotificationHandler.getTypeName(), pushNotificationHandler)
    }
}
