
# Thomas - a new A/B test library

*Go to [https://iheartradio.github.io/thomas/](https://iheartradio.github.io/thomas/) for more comprehensive documentation.*

[![Build Status](https://travis-ci.org/iheartradio/thomas.svg?branch=master)](https://travis-ci.org/iheartradio/thomas)
[![Gitter](https://badges.gitter.im/iheartradio/thomas.svg)](https://gitter.im/iheartradio/thomas?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge)

Thomas is a modular library for setting up an A/B test service for A/B test management and user group assignment. It also provides tools for results analysis. 

To use Thomas as a complete A/B test solution, a team needs to: 

1. be able to modify and deploy either play or http4s applications
2. have their own analytic platform and access to data  
3. be able to read and understand Scala code written in the functional programming paradigm.  
4. run and maintain a tiny MongoDB cluster (other data stores can be supported if users implement a data access layer).  

Still here? Ok, this is the set of features that differentiate Thomas from other existing A/B frameworks/libraries.  
  
## Features 

* [Real-time hash-based assignment](#real-time-hash-based-assignment)
* [Experiment evolution with consistent user assignments](#ab-test-experiment-evolution-with-consistent-user-assignments)
* [Bayesian analysis](#bayesian-analysis-utilities)
* [Eligibility control decoupled from user domain](#eligibility-control-decoupled-from-user-domain)
* [Mutually exclusive experiments](#mutually-exclusive-experiments)
* [Distributed group assignment with Spark (and Pyspark) support](#distributed-assignment--spark-support)
* [High Performance](#high-performance)
* [Modular and lightweight](#modular-and-lightweight)


### Real-time hash-based assignment

Thomas' A/B group assignment for users is always done real-time by a mathematical function that deterministically and uniformly distributes users into different groups. This function takes in a timestamp, a user Id and their relevant meta info, it then returns the assignments of all A/B test experiments running at that time. Thomas does not batch pre-assign users nor does it store the assignments anywhere. Every time you ask Thomas for group assignments of a user, it will run that mathematical function to answer you. 

This real-time assignment algorithm may seem mundane, but it has important implications. Specifically, it has to be strictly deterministic, as a mathematical function should be. With the same input, user info and timestamp, it guarantees to return the same assignments whenever it's called. To ensure that Thomas enforces that all A/B test experiments are read-only. To evolve an experiment, you have to terminate the existing one and start a new version. 

Other implications include:

1. Experiments' metadata is the only data that need to be persistent by Thomas. This design greatly simplified the data storage requirements in Thomas. 
2. Since there is no user lookup, Thomas is more scalable in regards to the number of end users. 
3. Assignments computation can be distributed as mathematical functions can be easily distributed. Thomas provides a thomas-client module that can compute assignments in parallel. More details below.

### A/B test experiment evolution with consistent user assignments

It's common that an A/B test consists of several rounds of experiments, sometimes introducing new treatment groups, sometimes changing the size of exiting groups. It is often preferable to avoid reassigning existing users within each group which will cause an abrupt discontinuity in their user experiences. Thomas supports such experiment evolution with consistent user assignments. 

When a new experiment of an A/B test is rolled out with a different group setup, Thomas can be set to either completely redistribute all users uniformly or keep the user assignment as consistent as possible. When a group is enlarged, all of its existing users will remain in it; when a group is shrunk, a minimally required portion of its existing users will be reassigned to other groups. The best way to demonstrate is through the following examples. 

#### Example 1: new treatment group
<img src="https://iheartradio.github.io/thomas/img/newGroup.png" width="300px" />

In this example, a new treatment group C is introduced. To make space for this new group the size of group B is decreased, while the size of A is unchanged.
In this scenario, Thomas will reassign part of the users in group B to the new group C while all users in group A remains in group A. 

#### Example 2: resize of existing groups
<img src="https://iheartradio.github.io/thomas/img/resizeGroup.png" width="300px" />

In this example, the sizes of the groups are changes. Group A is enlarged, group B is shrunk. In this scenario, Thomas will reassign just enough users from B to A but no more than necessary.   

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

### Eligibility control decoupled from user domain

Thomas does not require any direct integration with in house user meta-data storage. It uses a different mechanism to control users' eligibility for  A/B test experiments: when a client calls Thomas assignment service, it can pass in a small set of relevant metadata of the user. Experiments can be set with some eligibility criteria that filter users based on the metadata given. 

### Mutually exclusive experiments

Thomas provides an easy way to support mutually exclusive A/B test experiments, i.e., a user could join either experiment but not both. An experiment can be set with an arbitrary numeric range within the range 0 to 1. A/B test experiments with non-overlapping ranges are mutually exclusive with each other. 

### Distributed assignment / Spark support

Thomas provides a thomas-client that can pull down experiments metadata and calculate user assignments in parallel on local CPUs. It also provides a native Java API usable in Spark and Pyspark. This would allow users to efficiently batch process data according to A/B test group assignment without any extra workload to a centralized A/B test service.

### High Performance 

A/B test information retrieval is an overhead for all features running A/B tests. High performance is an uncompromisable top priority for Thomas since the very beginning. Thanks to the real-time assignment design, Thomas is able to easily keep all necessary data in memory when serving user assignment requests. Moreover, it's written in "sleek performant low-overhead Scala code with high-order functions that would run on anything. Period. End of sentence." 
When running on a MacBook Pro, with about two dozens of ongoing experiments, server-side response time 95th percentile is 4ms, 99th percentile is 8ms. When running on production servers at iHeartRadio, the 99th percentile server-side response time is 1ms.   

### Modular and lightweight

Thomas is developed in small modules so that users can pick and choose what they need. 
 - thomas-core: core logic and algorithms
 - thomas-http4s: library for creating an HTTP service using Http4s, also runs out of box
 - thomas-play: library for creating an HTTP service using Play framework
 - thomas-play-example: an example Thomas http service application using thomas-play and Play Framework 
 - thomas-mongo: data access layer using MongoDB
 - thomas-client: the distributed client that provides assignment by running the algorithm locally
 - thomas-analysis: Bayesian analysis utilities


## What's not included / still lacking in Thomas

* #### No built-in analytics
  Users of Thomas have to pick and implement their own analytics system. We took the approach that A/B test solution is decoupled from the analytics solution. 
  
* #### No Admin UI
  The HTTP service lib from Thomas makes it easier to set up a REST HTTP API service. The example play app also includes integration with Swagger-UI to make the API services more accessible with a rough Web UI automatically generated from the API service. If a fully functional web UI is needed for less technical persons to administrate A/B tests, it will have to be developed from scratch on top of the API service.
  
* #### No built-in authentication/authorization for management API
  Such an auth system has to be built into the Admin UI on top of the API service.     

* #### Documentation is WIP
  We are still in the process of adding documentation. 
 

## Thomas at iHeartRadio 

Thomas has been serving A/B tests at iHeartRadio for over a year. On a typical day, it runs 20-30 ongoing A/B test experiments with our 120+ million registered users. There were a couple of glitches during the early phase but has been stabilized and issue free for more than half a year. If you are considering running an in-house A/B test platform, please consider giving Thomas a try.  

## Ready to get started?

Please follow the guide [here](https://iheartradio.github.io/thomas/get-started.html). 
 
## Contribute

We welcome all feedback and questions. You can either [submit a github issue](https://github.com/iheartradio/thomas/issues/new) or raise it in Thomas' [gitter channel](ttps://gitter.im/iheartradio/thomas)  
Any contribution, big or small, are truly appreciated. We open source Thomas because we believe that a community can build a A/B test library better than a single organization. 
 

## Copyright and License

All code is available to you under the Apache 2 license, available in the COPY file. 

Copyright iHeartMedia + Entertainment, Inc., 2018-2019.
