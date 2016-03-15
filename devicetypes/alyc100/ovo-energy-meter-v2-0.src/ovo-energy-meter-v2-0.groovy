/**
 *  OVO Energy Meter V2.0
 *
 *  Copyright 2015 Alex Lee Yuk Cheung
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  VERSION HISTORY
 *  v2.0 - Initial V2.0 Release with OVO Energy (Connect) app
 *  v2.1 - Improve pricing calculations using contract info from OVO. Notification framework for high costs.
 *		   Enable alert for specified daily cost level breach.
 *	v2.1b - Allow cost alert level to be decimal
 *	v2.2 - Percentage comparison from previous cost values added into display
 *	v2.2.1 - Add current consumption price based on unit price from OVO account API not OVO live API
 *	v2.2.1b - Remove double negative on percentage values.
 */
preferences 
{
	input( "costAlertLevel", "decimal", title: "Set cost alert level (£)", description: "Send alert when daily cost reaches amount", required: false, defaultValue: 10.00 )
}

metadata {
	definition (name: "OVO Energy Meter V2.0", namespace: "alyc100", author: "Alex Lee Yuk Cheung") {
		capability "Polling"
		capability "Power Meter"
		capability "Refresh"
	}

	tiles(scale: 2) {
  		multiAttributeTile(name:"power", type:"generic", width:6, height:4, canChangeIcon: true) {
    		tileAttribute("device.power", key: "PRIMARY_CONTROL") {
      			attributeState "default", label: '${currentValue} W', icon:"st.Appliances.appliances17", backgroundColor:"#0a9928"
    		}
  		}
        
        valueTile("consumptionPrice", "device.consumptionPrice", decoration: "flat", width: 3, height: 2) {
			state "default", label: 'Curr. Cost:\n${currentValue}/h'
		}
        valueTile("unitPrice", "device.unitPrice", decoration: "flat", width: 3, height: 2) {
			state "default", label: 'Unit Price:\n${currentValue}'
		}
        
        valueTile("totalDemand", "device.averageDailyTotalPower", decoration: "flat", width: 3, height: 1) {
			state "default", label: 'Total Power:\n${currentValue} kWh'
		}
        valueTile("totalConsumptionPrice", "device.currentDailyTotalPowerCost", decoration: "flat", width: 3, height: 1) {
			state "default", label: 'Total Cost:\n${currentValue}'
		}
        
        valueTile("yesterdayTotalPower", "device.yesterdayTotalPower", decoration: "flat", width: 3, height: 1) {
			state "default", label: 'Yesterday Total Power :\n${currentValue} kWh'
		}
        valueTile("yesterdayTotalPowerCost", "device.yesterdayTotalPowerCost", decoration: "flat", width: 3, height: 1) {
			state "default", label: 'Yesterday Total Cost:\n${currentValue}'
		}
        
		standardTile("refresh", "device.power", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "default", label:'', action:"refresh.refresh", icon:"st.secondary.refresh"
		}
		main (["power"])
		details(["power", "consumptionPrice", "unitPrice", "totalDemand", "totalConsumptionPrice", "yesterdayTotalPower", "yesterdayTotalPowerCost", "refresh"])
	}
}

// parse events into attributes
def parse(String description) {
	log.debug "Parsing '${description}'"
	// TODO: handle 'power' attribute

}

// handle commands
def poll() {
	log.debug "Executing 'poll'"
	refreshLiveData()
}

def refresh() {
	log.debug "Executing 'refresh'"
	poll()    
}

def refreshLiveData() {

	def resp = parent.apiGET("https://live.ovoenergy.com/api/live/meters/${device.deviceNetworkId}/consumptions/instant")
	if (resp.status != 200) {
		log.error("Unexpected result in poll(): [${resp.status}] ${resp.data}")
		return []
	}
	
    	data.meterlive = resp.data
        
        //update unit price from OVO Live API
        def unitPriceBigDecimal = data.meterlive.consumption.unitPrice.amount as BigDecimal
        def unitPrice = (Math.round((unitPriceBigDecimal) * 100))/100
        def unitPriceCurrency = data.meterlive.consumption.unitPrice.currency
        unitPrice = String.format("%1.2f",unitPrice)
        
        //update unit price and standing charge from more up to date OVO Account API if available
        parent.updateLatestPrices()
        def latestUnitPrice = ((device.name.contains('Gas')) ? parent.getUnitPrice('GAS') : parent.getUnitPrice('ELECTRICITY')) as BigDecimal
        if (latestUnitPrice > 0) {
        	unitPriceBigDecimal = latestUnitPrice
        	unitPrice = String.format("%1.5f", unitPriceBigDecimal)
        }
        
        // get electricity readings
        def demand = ((int)Math.round((data.meterlive.consumption.demand as BigDecimal) * 1000))
        def consumptionPrice = (Math.round(((unitPriceBigDecimal as BigDecimal) * (data.meterlive.consumption.demand as BigDecimal)) * 100))/100
        def consumptionPriceCurrency = data.meterlive.consumption.consumptionPrice.currency
        
        //demand = String.format("%4f",demand)
        consumptionPrice = String.format("%1.2f",consumptionPrice)
        
        def standingCharge = ((device.name.contains('Gas')) ? parent.getStandingCharge('GAS') : parent.getStandingCharge('ELECTRICITY')) as BigDecimal
        log.debug "unitPrice: ${unitPriceBigDecimal} standingCharge: ${standingCharge}"
        // set local variables  
        
        sendEvent(name: 'power', value: "$demand", unit: "W")
        sendEvent(name: 'consumptionPrice', value: "£$consumptionPrice", displayed: false)
        sendEvent(name: 'unitPrice', value: "£$unitPrice", displayed: false)
        
        //Calculate power costs manually without need for terrible OVO API.
        if (data.dailyPowerHistory == null)
        {
        	data.dailyPowerHistory = [:]
        }
        if (data.yesterdayPowerHistory == null)
        {
        	data.yesterdayPowerHistory = [:]
        }
        //Get current hour
        //data.hour = null
        def currentHour = new Date().getAt(Calendar.HOUR_OF_DAY)
        if ((data.hour == null) || (data.hour != currentHour)) {
        	//Reset at midnight or initial call
        	if ((data.hour == null) || (currentHour == 0)) {  
            	//Store the day's power info as yesterdays
            	def totalPower = getTotalDailyPower()
            	data.yesterdayTotalPower = (Math.round((totalPower as BigDecimal) * 1000))/1000
                def newYesterdayTotalPowerCost = (Math.round((((totalPower as BigDecimal) * unitPriceBigDecimal) + standingCharge) * 100))/100
                def costYesterdayComparison = calculatePercentChange(newYesterdayTotalPowerCost as BigDecimal, data.yesterdayTotalPowerCost as BigDecimal)
                def formattedCostYesterdayComparison = costDailyComparison
                if (costYesterdayComparison >= 0) {
        			formattedCostYesterdayComparison = "+" + formattedCostYesterdayComparison
        		}
                data.yesterdayTotalPowerCost = newYesterdayTotalPowerCost
            	sendEvent(name: 'yesterdayTotalPower', value: "$data.yesterdayTotalPower", unit: "KWh", displayed: false)
        		sendEvent(name: 'yesterdayTotalPowerCost', value: "£$data.yesterdayTotalPowerCost (" + formattedCostYesterdayComparison + "%)", displayed: false)
                
                //Reset power history
                data.yesterdayPowerHistory =  data.dailyPowerHistory
                data.dailyPowerHistory = [:]
            }       	
        	data.hour = currentHour
            data.currentHourPowerTotal = 0
            data.currentHourPowerEntryNumber = 1
        }
        else {
       		data.currentHourPowerEntryNumber = data.currentHourPowerEntryNumber + 1      
        }
               
        data.currentHourPowerTotal = data.currentHourPowerTotal + (data.meterlive.consumption.demand as BigDecimal)
        data.dailyPowerHistory["Hour $data.hour"] = ((data.currentHourPowerTotal as BigDecimal) / data.currentHourPowerEntryNumber)
        
        def totalDailyPower = getTotalDailyPower()
        def hourCount = 0
        def costDailyComparison = calculatePercentChange(getTotalDailyPower() as BigDecimal, getYesterdayPower(data.hour) as BigDecimal)
        def formattedAverageTotalPower = (Math.round((totalDailyPower as BigDecimal) * 1000))/1000
        def formattedCurrentTotalPowerCost = (Math.round((((totalDailyPower as BigDecimal) * unitPriceBigDecimal) + standingCharge) * 100))/100
        def formattedCostDailyComparison = costDailyComparison
        if (costDailyComparison >= 0) {
        	formattedCostDailyComparison = "+" + formattedCostDailyComparison
        }
        
        //Send event to raise notification on high cost
        if (formattedCurrentTotalPowerCost > (getCostAlertLevelValue() as BigDecimal)) {
        	sendEvent(name: 'costAlertLevelPassed', value: "£${getCostAlertLevelValue()}")
        } else {
        	sendEvent(name: 'costAlertLevelPassed', value: "false")
        }
        
        formattedAverageTotalPower = String.format("%1.2f",formattedAverageTotalPower)
        formattedCurrentTotalPowerCost = String.format("%1.2f",formattedCurrentTotalPowerCost)
        formattedCurrentTotalPowerCost += " (" + formattedCostDailyComparison + "%)"
        
        sendEvent(name: 'averageDailyTotalPower', value: "$formattedAverageTotalPower", unit: "KWh", displayed: false)
        sendEvent(name: 'currentDailyTotalPowerCost', value: "£$formattedCurrentTotalPowerCost", displayed: false)
        
        log.debug "currentHour: $currentHour, data.hour: $data.hour, data.currentHourPowerTotal: $data.currentHourPowerTotal, data.currentHourPowerEntryNumber: $data.currentHourPowerEntryNumber, data.dailyPowerHistory: $data.dailyPowerHistory"
        log.debug "formattedAverageTotalPower: $formattedAverageTotalPower, formattedCurrentTotalPowerCost: $formattedCurrentTotalPowerCost"
       
}

private def getTotalDailyPower() {
	def totalDailyPower = 0
	data.dailyPowerHistory.each { hour, averagePower ->
    	totalDailyPower += averagePower
	};
    return totalDailyPower
}

private def getYesterdayPower(currentHour) {
	def totalDailyPower = 0
    for (int i=0; i<=currentHour.toInteger(); i++) {
    	if (data.yesterdayPowerHistory["Hour $i"] != null) totalDailyPower += data.yesterdayPowerHistory["Hour $i"]
    }    
    return totalDailyPower
}

private def calculatePercentChange(current, previous) {
	def delta = current - previous
    log.debug "Calculating %age: DELTA $delta CURRENT $current PREVIOUS $previous"
    if (previous != 0) {
    	return  Math.round((delta / previous) * 100)
    } else {
    	if (delta > 0) return 1000
        else if (delta == 0) return 0
        else return -1000
    }    
}

def getCostAlertLevelValue() {
	if (settings.costAlertLevel == null) {
    	return "10"
    } 
    return settings.costAlertLevel
}