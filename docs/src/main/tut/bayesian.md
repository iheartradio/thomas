---
layout: page
title:  "Bayesian Analysis"
section: "Bayesian"
position: 100
---

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

To use these utilities users need to implement integration with data retrieval from their analytical system.
 
The core class that provides the Bayesian analysis is [AnalysisAPI](https://iheartradio.github.io/thomas/api/com/iheart/thomas/client/AnalysisAPI.html) provided in `thomas-client`
 
This API is parameterized to the effect type `F[_]` and KPI's distribution type `K`   
```scala
 trait AnalysisAPI[F[_], K <: KPIDistribution] 
```
KPI stands for Key Performance Indicator, it is what we use to evaluate the effect of a treatment. One example is the conversion (or click through) rate, another example is average user engagement time. Each KPI can be modeled as a certain type of distribution. For example conversion rate is often modeled using the Beta distribution. One can choose to use the Gamma distribution to model the user engagement time. In fact these are two types of KPI distributions Thomas supports out of box at the time of writing. User can implement their own `KPIDistribution`, it will require knowledge of Bayesian inference and [Rainier](https://github.com/stripe/rainier) the MCMC library used in Thomas.

Thus depending on which type of KPI you are analyzing, you need to instantiate different type of `AnalysisAPI`. Take Gamma KPI distribution as an example:

```tut:silent
import com.iheart.thomas.client._
import com.iheart.thomas.client.AbtestClient.HttpServiceUrlsPlay
import cats.effect.IO
import com.iheart.thomas.analysis.Measurable.GammaMeasurable
import concurrent.ExecutionContext.Implicits.global


//these are Url to your Thomas http service instance that provides tests and kpi endpoints
val httpServiceUrl = new HttpServiceUrlsPlay("http://localhost/internal")
implicit val conextShift = IO.contextShift(global)

Http4SAbtestClient.resource[IO](httpServiceUrl, global).use { implicit client =>
  implicit val measurable: GammaMeasurable[IO] = null  //user needs to implement a GammaMeasurable, null used here so that code compiles 
  val analysisAPI = AnalysisAPI.defaultGamma[IO]
  //do something with analysisAPI
  IO(println("done")) 
}.unsafeRunSync

```
Note that in this code the user needs to provide a Client instance which will be used to retrieve A/B tests information and manage KPI from the Thomas service. Uer also needs to implement a `GammaMeasurable`, an alias for [Measurable](https://iheartradio.github.io/thomas/api/com/iheart/thomas/analysis/Measurable.html). This trait is responsible for getting the raw measurements of users of each group from the Analytics data.

Once we have an instance of `AnalysisAPI`, we can
 1. initialize a KPI with prior distribution using historical data
 2. update the KPI's prior distribution using more recent historical data
 3. assess the results of an A/B test
 
 For more detail regarding these methods please check [AnalysisAPI's API documentation](https://iheartradio.github.io/thomas/api/com/iheart/thomas/client/AnalysisAPI.html)
 

