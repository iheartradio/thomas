---
layout: page
title:  "Home"
section: "home"
position: 0
---


# Thomas - a new A/B test library


<img src="https://iheartradio.github.io/thomas/img/thomas-the-tank-engine.png" width="200px">

## Thomas is a modular Scala library for setting up a service for running A/B tests and multi-arm bandits. 
  
  
## Major Features 

- [Real-time hash-based assignment](#real-time-hash-based-assignment)
- [High performant and resilient assignment HTTP service](#high-performant-and-resilient-assignment-http-service)
- [A/B test experiment evolution with consistent user assignments](#a-b-test-experiment-evolution-with-consistent-user-assignments)
- [Bayesian analysis utilities](#bayesian-analysis-utilities)
- [Advanced eligibility control (targeted audience)](#advanced-eligibility-control--targeted-audience-)
- [Mutually exclusive experiments](#mutually-exclusive-experiments)
- [Distributed assignment / Spark support](#distributed-assignment---spark-support)
- [Web UI for management](#web-ui-for-management)
- [A/B test based dynamic configuration](#a-b-test-based-dynamic-configuration)
- [CLI for development automation](#cli-for-development-automation)
- [Bayesian multi-armed bandits with Kafka integration](#bayesian-multi-armed-bandits-with-kafka-integration)



### Real-time hash-based assignment

Thomas' A/B group assignment for users is always done real-time by a mathematical function that deterministically and uniformly distributes users into different groups. 
This function takes in a timestamp, a user Id and their relevant meta info, it then returns the assignments of all A/B test experiments running at that time. 
Thomas does not batch pre-assign users nor does it store the assignments anywhere. Every time you ask Thomas for group assignments of a user, 
it will run that mathematical function to answer you. 

### High performant and resilient assignment HTTP service

A/B test information retrieval is an overhead for all features running A/B tests. High performance is an uncompromisable top priority for Thomas
since the very beginning. Thomas' service is implemented using a high performant scala http framework called [http4s](https://http4s.org). 
More importantly, it keeps all necessary data in memory when serving user assignment requests to eliminate all IO overheads. 
On production Thomas handles 1K-2K requests per second at 1-2 ms response time. 

Thomas is built to be resilient when serving assignment requests. Using [Mau](https://github.com/kailuowang/mau), 
Thomas web service can withstand a relatively long backend DB downtime, during which the assignments requests continue to be served using 
last retrieved tests data. Thus when databases go offline, the management services become unavailable, but the assignment service remain functioning. 

### A/B test experiment evolution with consistent user assignments

It's common that an A/B test consists of several rounds of experiments, sometimes introducing new treatment groups, sometimes changing the size of exiting groups. It is often preferable to avoid reassigning existing users within each group which will cause an abrupt discontinuity in their user experiences. Thomas supports such experiment evolution with consistent user assignments. 

When a new experiment of an A/B test is rolled out with a different group setup, Thomas can be set to either completely redistribute all users uniformly or keep the user assignment as consistent as possible. When a group is enlarged, all of its existing users will remain in it; when a group is shrunk, a minimally required portion of its existing users will be reassigned to other groups. The best way to demonstrate is through the following examples. 

#### Example 1: new treatment group
<img src="https://iheartradio.github.io/thomas/img/newGroup.png" width="300px" />

In this example, a new treatment group C is introduced. To make space for this new group the size of group B is decreased, while the size of A is unchanged.
In this scenario, Thomas will reassign part of the users in group B to the new group C while all users in group A remains in group A. 

#### Example 2: resize of existing groups
<img src="https://iheartradio.github.io/thomas/img/resizeGroup.png" width="300px" />

In this example, the sizes of the groups are changed. Group A is enlarged, group B is shrunk. Thomas will reassign just enough users from B to A but no more than necessary.   

This also enables Thomas to double as a valid gradual feature roll-out system. A small treatment group can increase its size incrementally to roll out a feature. There are no disruptions of user experience in this process thanks to the consistent user assignment. 

Also note that Thomas does this without deviating from the real-time assignment algorithm. 

### Bayesian analysis utilities

Thomas provides a module thomas-analysis that can be used to perform Bayesian analysis for A/B test results. This library is built using the wonderful [Bayesian inference library Rainier](https://github.com/stripe/rainier).  Using this thomas-analysis, users can write analysis tools that compute a posterior distribution of the effect of the treatment v.s. control using Markov Chain Monte Carlo. 

In its essence, the A/B test analysis's main task is that, given the observed difference in analytics results between multiple treatment groups, pick the treatment that works best towards the goal for the product. The traditional frequentist approach has been trying to determine whether the difference observed is statistically significantly or not. The Bayesian analysis allows us to answer a different question: given the observed difference, what will be the risk of picking one treatment over another. In another sentence, what will be the maximum reduction of KPI in the scenario when one treatment is chosen over another but the real difference is the opposite of the one saw in the experiment.     


The Bayesian A/B analysis tool in Thomas produces results that look like the following.

```
 Group:   Treatment A
 Count:   53179
 Chance of better than control: 0.82692
 Expected Benefit: 0.03209179240152402
 Median Benefit: 0.02028449645941638
 Risk of Using: -0.014472377020694363
 Risk of Not Using: -0.05524666066837561
 Plot:
|                                                                                
|                                 ○                                              
|                                 ○○                                             
|                             ∘○· ○○ ∘                                           
|                             ○○○○○○ ○·                                          
|                          ∘∘·○○○○○○○○○··                                        
|                         ∘○○○○○○○○○○○○○○∘                                       
|                        ∘○○○○○○○○○○○○○○○○·○                                     
|                       ·○○○○○○○○○○○○○○○○○○○○                                    
|                      ○○○○○○○○○○○○○○○○○○○○○○                                    
|                     ·○○○○○○○○○○○○○○○○○○○○○○                                    
|                   ∘∘○○○○○○○○○○○○○○○○○○○○○○○∘∘                                  
|                 ∘∘○○○○○○○○○○○○○○○○○○○○○○○○○○○∘                                 
|                ·○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○∘                                
|               ∘○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○∘                               
|              ∘○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○∘·                             
|            ·∘○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○∘·                           
|            ○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○                          
|        ·∘○∘○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○∘∘                        
|    ·∘·○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○···                    
|··∘∘○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○∘∘··········  ·   ·
|--------|--------|--------|--------|--------|--------|--------|--------|--------
 -0.039   -0.023   -0.007   0.010    0.026    0.042    0.058    0.075    0.091  

```

Note that to use these utilities users need to implement integration with data retrieval from their analytical system. 

### Advanced eligibility control (targeted audience)

Eligibility control determines which users are eligible for which experiments.
When a feature is not available to certain users, it is desirable to avoid
providing any group assignment for these users.
For one thing, it may become a source of false report in analytic data.
This feature also allows targeting a subset of all users for a certain experiment.

Thomas does not require any direct integration with in house user meta-data storage. When a client calls Thomas assignment service,
it can pass in a small set of relevant metadata of the user. Experiments can be set with some eligibility criteria that filter users based on the metadata given. 

Here is an example of an assignment request that comes with a piece of meta data:

```json
{
  "userId": "USERID", 
  "meta": { 
     "deviceId": "1231541sdfsd",
     "platform": "iOS",
     "clientVersion": "1.2.3"
   } 
}
```
This JSON object is expected to be flat with string values only.

This then can be used for eligibility control. A/B tests can be set with a
filtering criteria to determine which users are eligible based on
the user meta passed in. These criteria is written in query language in JSON format.


In A/B tests you can set a user meta criteria using a Json based query language.
This query language similiar to MongoDB's json query.
Other than exact match it supports `%regex`, `%in`, `%versionStart`, `%versionRange`,
as well as combinators `%and` and `%or`  
The following example lists most of the criteria.

```json
{
     
       "sex" : "female",                   //matches users whose "sex" field is exactly "female"
       
       "description" : {                  
          "%regex" : "[shinny|fancy]"      //matches users whose "description" field matches a regex "[shinny|fancy]"
       },
       
       "device" : {
         "%in": ["iphone","ipad"]          //matches users whose "device" is one of the two strings "iphone" and "ipad"
       },
       
       "%or": {                            //matches users whose "city" is "LA" or "state" is "NY"
         "city": "LA",
         "state": "NY"
       },
       
       "clientVer": {
         "%versionStart" : "1.0.0"         //special filter for version strings. Matches users whose "clientVer" is later than "1.0.0"
       },

       "androidVer": {
         "%versionRange" : ["2.0", "3.1"] //special filter for version strings. Matches users whose "androidVer" is between than "2.0" and "3.1"
       }
   
}
```

The combinator `%or` here is written as an object, which is convenient but also means field names cannot be duplicated.
In case where you need to have multiple criteria on the same field within an `%or`, you also use an array of objects.
For example:
```json
{
   "%or": [                            
       { "city": "LA" },
       { "city": "NY" }
   ]
}
```


### Mutually exclusive experiments

Thomas provides an easy way to support mutually exclusive A/B test experiments, i.e., a user could join either experiment but not both. An experiment can be set with an arbitrary numeric range within the range 0 to 1. A/B test experiments with non-overlapping ranges are mutually exclusive with each other. 

### Distributed assignment / Spark support

Thomas provides a `thomas-client` that can pull down experiments metadata and calculate user assignments in parallel on local CPUs. 
It also provides a Java API in `thomas-spark` usable in Spark and Pyspark. This would allow users to efficiently batch process data according to A/B test group assignment without any extra workload to a centralized A/B test service.
For more details, go to [this FAQ section](FAQ.html#how-to-run-distributed-group-assignments)

### Web UI for management

Thomas supports tests management through a REST Http service. It also provides a Web UI with multi-tier per feature access control.

### A/B test based dynamic configuration

Feature settings can be embedded into the A/B groups, and is returned together with the A/B group assignment.
Application code can read these feature settings instead of toggling feature settings based on assigned A/B group. 
This setup has several advantages:

- It decouples client code from A/B test knowledge
- Changing feature settings no longer require modifying client code
- It keeps and manages feature settings and A/B groups at the same place, i.e. A/B test Web UI.

### CLI for development automation

Thomas provides a command line interface to support development automation, e.g. programmable test management.  

### Bayesian multi-armed bandits with Kafka integration

Thomas can run multi-armed bandits using Thompson Sampling - distribute users to treatments based on the probability distribution of each treatment being the most optimal.
This feature is still being actively developed. 

### Modular and lightweight

Thomas is developed in small modules so that users can pick what they need. 
 - **thomas-core**: core logic and algorithms
 - **thomas-http4s**: library for creating an HTTP service using Http4s, also runs out of box
 - **thomas-http4s-example**: an example Thomas http service application using thomas-play and Play Framework
 - **thomas-play**: library for creating an HTTP service using Play framework
 - **thomas-play-example**: an example Thomas http service application using thomas-play and Play Framework 
 - **thomas-mongo**: data access layer using MongoDB
 - **thomas-client**: the distributed client that provides assignment by running the algorithm locally
 - **thomas-analysis**: Bayesian analysis utilities
 - **thomas-cli**: Command line interface using `thomas-client`
 - **thomas-bandit**: WIP core logic for Bayesian multi-arm bandit
 - **thomas-spark**: Integration with Spark for distributed batch assignment
 - **thomas-dynamo**: WIP dynamo DB based Data access layer for multi-arm bandit related data
 - **thomas-stream**: WIP streaming process of analytics data
 - **thomas-kafka**: WIP integration of thomas stream and kafka for bandit
 - **thomas-testkit**: Supports for testing systems built on Thomas
 - **thomas-stress**: Stress tests for Thomas web services
 


## Ready to get started?

Please follow the guide [here](https://iheartradio.github.io/thomas/get-started.html). 
 
## Contribute

We welcome all feedback and questions. You can either [submit a github issue](https://github.com/iheartradio/thomas/issues/new) or raise it in Thomas' [gitter channel](ttps://gitter.im/iheartradio/thomas)  
Any contribution, big or small, are truly appreciated. We open source Thomas because we believe that a community can build a A/B test library better than a single organization. 
 
## Code of conduct 

See our [Code of Conduct file](https://github.com/iheartradio/thomas/blob/master/CODE_OF_CONDUCT.md).

## Copyright and License

All code is available to you under the Apache 2 license, available in the COPY file. 

Copyright iHeartMedia + Entertainment, Inc., 2018-2019.
