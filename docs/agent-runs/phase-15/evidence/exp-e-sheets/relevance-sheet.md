# Experiment E relevance sheet

For each question, judge each item on its own: is it relevant evidence for answering the question?
Write 1 (relevant) or 0 (not relevant) in the `relevant` column of relevance-template.csv for its item key,
then save the file as labels/relevance-e-v1.csv. Items are in a fixed random order.

## B01

Question: How many distinct coauthors does Jack J. Dongarra have?

### Item B01-i01

Evidence type: IDENTITY_RESOLUTION
Facts:
- candidateCount: 17
- decision: RESOLVED_DOMINANT
- displayName: Jack J. Dongarra
- dominantShare: 0.9
- identityConfidence: 0.9300527553067827
- minWorks: 5
- name: Jack J. Dongarra
- nameKey: name-v1:jack j dongarra
- policyVersion: dominant-name-v1
- researcherId: scr:canon-v1.er-v2:6de4012bbe8608f1d49d18cf9a40a763
- topShare: 0.9575596816976127
- totalWorks: 377
- works: 361

### Item B01-i02

Evidence type: PROFILE
Facts:
- contextFormat: semantic-context-v1
- documentType: researcher-profile
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: researcher-profile-v1
- window: all-years
Text:

> Researcher profile: Jack J. Dongarra, 2000-2026 (window all-years). Jack J. Dongarra has 340 publications in the DBLP subset in this window, first in 2000 and latest in 2025. Collaboration: 499 distinct coauthors; 1430 coauthor occurrences in total; 4.21 coauthors per paper on average; at most 64 on one paper; weighted degree 1430. Network position: coreness 64; a connected component of 141367 researchers, the largest. Community: a collaboration community of 156 researchers; 436 coauthors in other communities; 132 communities connected; participation coefficient 0.962. Frequent coauthors: Stanimire Tomov (72 joint works); George Bosilca (67 joint works); Piotr Luszczek (54 joint works). Frequent topics: Parallel Computing and Optimization Techniques (175 works); Matrix Theory and Algorithms (66 works); Distributed and Parallel Computing Systems (43 works). Frequent venues: IPDPS (64 works); SC (56 works); Int. J. High Perform. Comput. Appl. (49 works). Institutions on record (any year): University of Tennessee at Knoxville; Oak Ridge National Laboratory; University of Manchester. Recent works: "Efficient Embedding Initialization via Dominant Eigenvector Projections." (2025); "Advancements of PAPI for the exascale generation." (2025); "Automated Data Analysis for Defining Performance Metrics from Raw Hardware Events." (2024).

### Item B01-i03

Evidence type: METRIC_RECORD
Facts:
- averageCoauthorsPerPaper: 4.205882352941177
- communitiesConnected: 132
- communityId: comm-377de7bb113221b85e4f0ed00f9f60be
- communityRunId: run-83b496169099a614ec519133077a1993
- communitySize: 156
- componentId: comp-4a11d3713f9891c5c60312810523ce70
- componentSize: 141367
- coreness: 64
- crossCommunityDegree: 436
- displayName: Jack J. Dongarra
- distinctCoauthorCount: 499
- graphDegree: 499
- maximumCoauthorsOnOnePaper: 64
- maximumTeamSize: 65
- maximumTeamWorkIds: dblp:journals/ijhpca/DongarraBM11
- metricVersion: researcher-metrics-window-v1@run-d7865ad3f5f8195546c1dab817328ee5
- participationCoefficient: 0.9622451315456564
- publicationCount: 340
- researcherId: scr:canon-v1.er-v2:6de4012bbe8608f1d49d18cf9a40a763
- totalCoauthorOccurrences: 1430
- weightedDegree: 1430
- window: all-years
- windowEndYear: 2026
- windowStartYear: 2000

## B02

Question: How many distinct coauthors did Rajkumar Buyya have in 2015-2026?

### Item B02-i01

Evidence type: IDENTITY_RESOLUTION
Facts:
- candidateCount: 11
- decision: RESOLVED_DOMINANT
- displayName: Rajkumar Buyya
- dominantShare: 0.9
- identityConfidence: 0.9300527553067827
- minWorks: 5
- name: Rajkumar Buyya
- nameKey: name-v1:rajkumar buyya
- policyVersion: dominant-name-v1
- researcherId: scr:canon-v1.er-v2:bede83f65bedfab8f7fc802f295ee293
- topShare: 0.964516129032258
- totalWorks: 310
- works: 299

### Item B02-i02

Evidence type: METRIC_RECORD
Facts:
- averageCoauthorsPerPaper: 3.11875
- communitiesConnected: 115
- communityId: comm-6c0d733accc54056b8ef3df62e1dd3da
- communityRunId: run-83b496169099a614ec519133077a1993
- communitySize: 292
- componentId: comp-b7aa22e5808836b7fa46ce1a75d80c87
- componentSize: 98812
- coreness: 12
- crossCommunityDegree: 257
- displayName: Rajkumar Buyya
- distinctCoauthorCount: 295
- graphDegree: 295
- maximumCoauthorsOnOnePaper: 7
- maximumTeamSize: 8
- maximumTeamWorkIds: dblp:journals/concurrency/VermaGNRIRCB16 dblp:journals/fgcs/MangalaRARBVIP26 dblp:journals/jpdc/LiASPJBMR19 dblp:journals/tpds/ZhouFLXTBQS25
- metricVersion: researcher-metrics-window-v1@run-d7865ad3f5f8195546c1dab817328ee5
- participationCoefficient: 0.9638149956908935
- publicationCount: 160
- researcherId: scr:canon-v1.er-v2:bede83f65bedfab8f7fc802f295ee293
- totalCoauthorOccurrences: 499
- weightedDegree: 499
- window: 2015-2026
- windowEndYear: 2026
- windowStartYear: 2015

### Item B02-i03

Evidence type: PROFILE
Facts:
- contextFormat: semantic-context-v1
- documentType: researcher-profile
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: researcher-profile-v1
- window: all-years
Text:

> Researcher profile: Rajkumar Buyya, 2000-2026 (window all-years). Rajkumar Buyya has 293 publications in the DBLP subset in this window, first in 2000 and latest in 2026. Collaboration: 419 distinct coauthors; 798 coauthor occurrences in total; 2.72 coauthors per paper on average; at most 7 on one paper; weighted degree 798. Network position: coreness 17; a connected component of 141367 researchers, the largest. Community: a collaboration community of 617 researchers; 362 coauthors in other communities; 184 communities connected; participation coefficient 0.967. Frequent coauthors: Rajiv Ranjan 0001 (20 joint works); Rodrigo N. Calheiros (20 joint works); Dawei Sun 0001 (17 joint works). Frequent topics: Cloud Computing and Resource Management (132 works); Distributed and Parallel Computing Systems (71 works); IoT and Edge/Fog Computing (22 works). Frequent venues: Future Gener. Comput. Syst. (77 works); Concurr. Comput. Pract. Exp. (40 works); CCGRID (33 works). Institutions on record (any year): The University of Melbourne; Monash University; The University of Melbourne, Australia. Recent works: "REACH: Reinforcement Learning for Adaptive Microservice Rescheduling in the Cloud-Edge Continuum." (2026); "Coded Redundancy for Reducing Task Average Latency in Distributed Stream Processing Systems." (2026); "Predictscale: Proactive Autoscaling Via Workload Forecasting for Edge Microservices Application." (2026).

## B03

Question: Which researcher had the most distinct coauthors in 2000-2014, and how many?

### Item B03-i01

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: topic-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: topic-institution-summary-v1
- window: 2000-2014
Text:

> Topic: Financial Markets and Investment Strategies (field Economics, Econometrics and Finance, subfield Finance), 2000-2014 (window 2000-2014). 6 works by 19 researchers in the DBLP subset have this topic. Most active researchers: Latha Shanker (2 works); Narayanaswamy Balakrishnan 0001 (2 works); Alan S. Wagner (1 work). Frequent venues: Concurr. Comput. Pract. Exp. (2 works); IPDPS (2 works); SC (2 works).

### Item B03-i02

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: topic-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: topic-institution-summary-v1
- window: 2000-2014
Text:

> Topic: Markov Chains and Monte Carlo Methods (field Mathematics, subfield Statistics and Probability), 2000-2014 (window 2000-2014). 6 works by 15 researchers in the DBLP subset have this topic. Most active researchers: Abhir H. Bhalerao (2 works); Jonathan M. R. Byrd (2 works); Stephen A. Jarvis (2 works). Frequent venues: IPDPS (2 works); Concurr. Comput. Pract. Exp. (1 work); J. Parallel Distributed Comput. (1 work).

### Item B03-i03

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: topic-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: topic-institution-summary-v1
- window: 2000-2014
Text:

> Topic: Advanced Text Analysis Techniques (field Computer Science, subfield Artificial Intelligence), 2000-2014 (window 2000-2014). 4 works by 12 researchers in the DBLP subset have this topic. Most active researchers: Huizhe Xiao (2 works); Ning Fang (2 works); Xiangfeng Luo (2 works). Frequent venues: Concurr. Comput. Pract. Exp. (2 works); Future Gener. Comput. Syst. (1 work); HPDC (1 work).

### Item B03-i04

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: topic-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: topic-institution-summary-v1
- window: 2000-2014
Text:

> Topic: Mobile Crowdsensing and Crowdsourcing (field Computer Science, subfield Computer Science Applications), 2000-2014 (window 2000-2014). 25 works by 83 researchers in the DBLP subset have this topic. Most active researchers: Antonio Fernández Anta (2 works); Chryssis Georgiou (2 works); Evgenia Christoforou (2 works). Frequent venues: ICDCS (5 works); IEEE Trans. Parallel Distributed Syst. (5 works); Middleware (4 works).

### Item B03-i05

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: topic-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: topic-institution-summary-v1
- window: 2000-2014
Text:

> Topic: Computational and Text Analysis Methods (field Social Sciences, subfield General Social Sciences), 2000-2014 (window 2000-2014). 2 works by 8 researchers in the DBLP subset have this topic. Most active researchers: Alexander Crowell (1 work); Atul Prakash 0001 (1 work); Beth A. Plale (1 work). Frequent venues: HPDC (1 work); Parallel Comput. (1 work).

### Item B03-i06

Evidence type: RANKING_RECORD
Facts:
- filteredBy: 
- lastStoredValue: 147
- metric: distinctCoauthorCount
- metricVersion: researcher-ranking-v1@run-1d6a86491812ff94887270c1ab885e69
- rank.1.displayName: Ian T. Foster
- rank.1.researcherId: scr:canon-v1.er-v2:8c41e7402418b6a74ad89d1b987f6f44
- rank.1.storedRank: 1
- rank.1.value: 349
- returnedSize: 1
- storedSize: 50
- tiedAtCut: false
- window: 2000-2014
- windowEndYear: 2014
- windowStartYear: 2000

## B04

Question: What is the largest number of authors on a single paper in the corpus?

### Item B04-i01

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: topic-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: topic-institution-summary-v1
- window: all-years
Text:

> Topic: Advanced Authentication Protocols Security (field Computer Science, subfield Computer Networks and Communications), 2000-2026 (window all-years). 490 works by 1298 researchers in the DBLP subset have this topic. Most active researchers: Saru Kumari (18 works); Ashok Kumar Das (14 works); Xiong Li 0002 (11 works). Frequent venues: J. Supercomput. (130 works); Future Gener. Comput. Syst. (81 works); Concurr. Comput. Pract. Exp. (54 works).

### Item B04-i02

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: topic-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: topic-institution-summary-v1
- window: all-years
Text:

> Topic: Advanced Text Analysis Techniques (field Computer Science, subfield Artificial Intelligence), 2000-2026 (window all-years). 40 works by 136 researchers in the DBLP subset have this topic. Most active researchers: Xiangfeng Luo (5 works); Hai Zhuge (3 works); Chang-Zhi Wang (2 works). Frequent venues: J. Supercomput. (13 works); Concurr. Comput. Pract. Exp. (10 works); Clust. Comput. (7 works).

### Item B04-i03

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: topic-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: topic-institution-summary-v1
- window: all-years
Text:

> Topic: Academic integrity and plagiarism (field Social Sciences, subfield Safety Research), 2000-2026 (window all-years). 2 works by 6 researchers in the DBLP subset have this topic. Most active researchers: Chih-Yung Chang (1 work); Diptendu Sinha Roy (1 work); Marko Misic (1 work). Frequent venues: Concurr. Comput. Pract. Exp. (1 work); J. Supercomput. (1 work).

### Item B04-i04

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: topic-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: topic-institution-summary-v1
- window: all-years
Text:

> Topic: Authorship Attribution and Profiling (field Computer Science, subfield Artificial Intelligence), 2000-2026 (window all-years). 12 works by 45 researchers in the DBLP subset have this topic. Most active researchers: Abdulaziz Alghamdi (1 work); Abhinay Pandya (1 work); Ahod Alghuried (1 work). Frequent venues: Future Gener. Comput. Syst. (4 works); Concurr. Comput. Pract. Exp. (3 works); Clust. Comput. (2 works).

### Item B04-i05

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: topic-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: topic-institution-summary-v1
- window: all-years
Text:

> Topic: Semantic Web and Ontologies (field Computer Science, subfield Artificial Intelligence), 2000-2026 (window all-years). 247 works by 839 researchers in the DBLP subset have this topic. Most active researchers: Hai Zhuge (9 works); Xiangfeng Luo (5 works); Andrew Flahive (4 works). Frequent venues: Future Gener. Comput. Syst. (70 works); Concurr. Comput. Pract. Exp. (43 works); Clust. Comput. (26 works).

### Item B04-i06

Evidence type: RANKING_RECORD
Facts:
- filteredBy: 
- lastStoredValue: 286
- metric: maximumCoauthorsOnOnePaper
- metricVersion: researcher-ranking-v1@run-1d6a86491812ff94887270c1ab885e69
- rank.1.displayName: Matthew Dunkman
- rank.1.maximumTeamSize: 287
- rank.1.researcherId: scr:canon-v1.er-v2:00081c1918dc4d7f9fbe9bf0ff3a429b
- rank.1.storedRank: 1
- rank.1.value: 286
- returnedSize: 1
- storedSize: 50
- tiedAtCut: true
- window: all-years
- windowEndYear: 2026
- windowStartYear: 2000

## B05

Question: What is the largest team Ian T. Foster has published with?

### Item B05-i01

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: paper-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: paper-abstract-v1
- window: all-years
Text:

> Paper: "Flux Fiction: Hopping Toward Storage Graph Scheduling With El Capitan's Rabbits." (2026), inproceedings, published in HPDC. Authors (10): W. Jay Ashworth; Ian Lumsden; Jim Garlick; Mark Grondona; Olga Pearce; Stephanie Brink; Daniel J. Milroy; Tapasya Patki; Thomas Scogland; Michela Taufer. Topics: Distributed and Parallel Computing Systems.

### Item B05-i02

Evidence type: METRIC_RECORD
Facts:
- averageCoauthorsPerPaper: 6.713740458015267
- communitiesConnected: 157
- communityId: comm-091d57242a0bf670fdc523d01b139bb5
- communityRunId: run-83b496169099a614ec519133077a1993
- communitySize: 190
- componentId: comp-4a11d3713f9891c5c60312810523ce70
- componentSize: 141367
- coreness: 51
- crossCommunityDegree: 705
- displayName: Ian T. Foster
- distinctCoauthorCount: 770
- graphDegree: 770
- maximumCoauthorsOnOnePaper: 49
- maximumTeamSize: 50
- maximumTeamWorkIds: dblp:journals/concurrency/MoreauLA08
- metricVersion: researcher-metrics-window-v1@run-d7865ad3f5f8195546c1dab817328ee5
- participationCoefficient: 0.9717321639399561
- publicationCount: 262
- researcherId: scr:canon-v1.er-v2:8c41e7402418b6a74ad89d1b987f6f44
- totalCoauthorOccurrences: 1759
- weightedDegree: 1759
- window: all-years
- windowEndYear: 2026
- windowStartYear: 2000

### Item B05-i03

Evidence type: WORK_RECORD
Facts:
- title: Special Issue: The First Provenance Challenge.
- workId: dblp:journals/concurrency/MoreauLA08
- year: 2008

### Item B05-i04

Evidence type: PROFILE
Facts:
- contextFormat: semantic-context-v1
- documentType: researcher-profile
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: researcher-profile-v1
- window: all-years
Text:

> Researcher profile: Ian T. Foster, 2000-2026 (window all-years). Ian T. Foster has 262 publications in the DBLP subset in this window, first in 2000 and latest in 2026. Collaboration: 770 distinct coauthors; 1759 coauthor occurrences in total; 6.71 coauthors per paper on average; at most 49 on one paper; weighted degree 1759. Network position: coreness 51; a connected component of 141367 researchers, the largest. Community: a collaboration community of 190 researchers; 705 coauthors in other communities; 157 communities connected; participation coefficient 0.972. Frequent coauthors: Kyle Chard (70 joint works); Rajkumar Kettimuthu (38 joint works); Ryan Chard (33 joint works). Frequent topics: Distributed and Parallel Computing Systems (105 works); Parallel Computing and Optimization Techniques (33 works); Cloud Computing and Resource Management (24 works). Frequent venues: SC (68 works); HPDC (45 works); IPDPS (22 works). Institutions on record (any year): Argonne National Laboratory; University of Chicago; Argonne National Laboratory. Recent works: "Towards Transparent Checkpointing with AI-driven Code Generation." (2026); "StreamGuard: Low-Overhead Resilience for Real-time HPC Data Streams." (2026); "Empowering Scientific Workflows with Federated Agents." (2026).

### Item B05-i05

Evidence type: IDENTITY_RESOLUTION
Facts:
- candidateCount: 10
- decision: RESOLVED_DOMINANT
- displayName: Ian T. Foster
- dominantShare: 0.9
- identityConfidence: 0.9300527553067827
- minWorks: 5
- name: Ian T. Foster
- nameKey: name-v1:ian t foster
- policyVersion: dominant-name-v1
- researcherId: scr:canon-v1.er-v2:8c41e7402418b6a74ad89d1b987f6f44
- topShare: 0.9679715302491103
- totalWorks: 281
- works: 272

### Item B05-i06

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: paper-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: paper-abstract-v1
- window: all-years
Text:

> Paper: "IoTwins: Design and Implementation of a Platform for the Management of Digital Twins in Industrial Scenarios." (2021), inproceedings, published in CCGRID. Authors (17): Andrea Borghesi; Andrea Borghesi; Andrea Borghesi; Giuseppe Di Modica; Paolo Bellavista; Varun Gowtham; Alexander Willner; Daniel Nehls; Florian Kintzler; Stephan Cejka; and 7 more authors. Topics: Digital Transformation in Industry.

### Item B05-i07

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: paper-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: paper-abstract-v1
- window: all-years
Text:

> Paper: "The International Exascale Software Project roadmap." (2011), article, published in Int. J. High Perform. Comput. Appl.. Authors (74): Jack J. Dongarra; Peter H. Beckman; Terry Moore; Patrick Aerts; Giovanni Aloisio; Jean-Claude Andre; David Barkai; Jean-Yves Berthou; Taisuke Boku; Bertrand Braunschweig; and 64 more authors. Topics: Parallel Computing and Optimization Techniques.

### Item B05-i08

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: paper-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: paper-abstract-v1
- window: all-years
Text:

> Paper: "Swift/T: Large-Scale Application Composition via Distributed-Memory Dataflow Processing." (2013), inproceedings, published in CCGRID. Authors (6): Justin M. Wozniak; Timothy G. Armstrong; Michael Wilde; Daniel S. Katz; Ewing L. Lusk; Ian T. Foster. Topics: Parallel Computing and Optimization Techniques.

### Item B05-i09

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: paper-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: paper-abstract-v1
- window: all-years
Text:

> Paper: "Combining in-situ and in-transit processing to enable extreme-scale scientific analysis." (2012), inproceedings, published in SC. Authors (22): Janine Bennett; Hasan Abbasi; Peer-Timo Bremer; Ray W. Grout; Attila Gyulassy; Tong Jin; Tong Jin 0002; Tong Jin; Scott Klasky; Hemanth Kolla; and 12 more authors. Topics: Scientific Computing and Data Management.

## B06

Question: What was the largest team Franck Cappello worked on in 2000-2014?

### Item B06-i01

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: paper-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: paper-abstract-v1
- window: all-years
Text:

> Paper: "Preface of Special Issue on Highlights from the Joint-Laboratory on Extreme Scale Computing." (2025), article, published in Future Gener. Comput. Syst.. Authors (3): Franck Cappello; Ruth Partzsch; Daniel S. Katz. Topics: Parallel Computing and Optimization Techniques.

### Item B06-i02

Evidence type: IDENTITY_RESOLUTION
Facts:
- candidateCount: 17
- decision: RESOLVED_DOMINANT
- displayName: Franck Cappello
- dominantShare: 0.9
- identityConfidence: 0.9300527553067827
- minWorks: 5
- name: Franck Cappello
- nameKey: name-v1:franck cappello
- policyVersion: dominant-name-v1
- researcherId: scr:canon-v1.er-v2:84a0a600f9fc8efeb0a87b085e316bfd
- topShare: 0.9391634980988594
- totalWorks: 263
- works: 247

### Item B06-i03

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: paper-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: paper-abstract-v1
- window: all-years
Text:

> Paper: "The International Exascale Software Project roadmap." (2011), article, published in Int. J. High Perform. Comput. Appl.. Authors (74): Jack J. Dongarra; Peter H. Beckman; Terry Moore; Patrick Aerts; Giovanni Aloisio; Jean-Claude Andre; David Barkai; Jean-Yves Berthou; Taisuke Boku; Bertrand Braunschweig; and 64 more authors. Topics: Parallel Computing and Optimization Techniques.

### Item B06-i04

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: paper-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: paper-abstract-v1
- window: all-years
Text:

> Paper: "Scaling the hartree-fock matrix build on summit." (2020), inproceedings, published in SC. Authors (9): Giuseppe M. J. Barca; David L. Poole; Jorge L. Galvez Vallejo; Melisa Alkan; Colleen Bertoni; Alistair P. Rendell; Mark S. Gordon; M.S. Gordon; Mark S. Gordon. Topics: Parallel Computing and Optimization Techniques.

### Item B06-i05

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: paper-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: paper-abstract-v1
- window: all-years
Text:

> Paper: "4-Free Strong Digraphs with the Maximum Size." (2023), article, published in Parallel Process. Lett.. Authors (5): Qifan Zhang; Qifan Zhang; Qifan Zhang; Liqiong Xu; Yuqing Lin 0001. Topics: Advanced Graph Theory Research.

### Item B06-i06

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: paper-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: paper-abstract-v1
- window: all-years
Text:

> Paper: "RICH: implementing reductions in the cache hierarchy." (2020), inproceedings, published in ICS. Authors (5): Vladimir Dimic; Miquel Moretó; Marc Casas; Jan Ciesko; Mateo Valero. Topics: Parallel Computing and Optimization Techniques.

### Item B06-i07

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: paper-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: paper-abstract-v1
- window: all-years
Text:

> Paper: "Chameleon: Adaptive Bidirectional Pipeline Parallelism for Edge Collaborative Training." (2026), inproceedings, published in ICPP. Authors (6): Tao Liu; Xiangpeng Hou; Liying Li 0002; Peijin Cong; Dan Meng 0001; Junlong Zhou. Topics: Parallel Computing and Optimization Techniques.

### Item B06-i08

Evidence type: WORK_RECORD
Facts:
- title: The International Exascale Software Project roadmap.
- workId: dblp:journals/ijhpca/DongarraBM11
- year: 2011

### Item B06-i09

Evidence type: PROFILE
Facts:
- contextFormat: semantic-context-v1
- documentType: researcher-profile
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: researcher-profile-v1
- window: all-years
Text:

> Researcher profile: Franck Cappello, 2000-2026 (window all-years). Franck Cappello has 234 publications in the DBLP subset in this window, first in 2000 and latest in 2026. Collaboration: 590 distinct coauthors; 1382 coauthor occurrences in total; 5.91 coauthors per paper on average; at most 64 on one paper; weighted degree 1382. Network position: coreness 64; a connected component of 141367 researchers, the largest. Community: a collaboration community of 397 researchers; 383 coauthors in other communities; 109 communities connected; participation coefficient 0.857. Frequent coauthors: Xin Liang 0001 (37 joint works); Dingwen Tao (35 joint works); Kai Zhao 0008 (33 joint works). Frequent topics: Distributed systems and fault tolerance (74 works); Advanced Data Compression Techniques (47 works); Parallel Computing and Optimization Techniques (30 works). Frequent venues: SC (42 works); IPDPS (32 works); CLUSTER (25 works). Institutions on record (any year): Argonne National Laboratory; Institut national de recherche en sciences et technologies du numérique; University of Illinois Urbana-Champaign. Recent works: "Bridging Information Theory and Practice for Scientific Lossy Compression." (2026); "TZ: Achieving High-Ratio Scientific Data Compression on GPUs with Global Data Decomposition." (2026); "OPAL: On-demand Progressive Accelerated Scientific Lossy Compression." (2026).

### Item B06-i10

Evidence type: METRIC_RECORD
Facts:
- averageCoauthorsPerPaper: 4.7875
- communitiesConnected: 34
- communityId: comm-e2d7d1c5967a7159bdb71f6d8b138d6f
- communityRunId: run-83b496169099a614ec519133077a1993
- communitySize: 203
- componentId: comp-af5aeb020fac13dee03b6881edf4d3ab
- componentSize: 46062
- coreness: 64
- crossCommunityDegree: 117
- displayName: Franck Cappello
- distinctCoauthorCount: 202
- graphDegree: 202
- maximumCoauthorsOnOnePaper: 64
- maximumTeamSize: 65
- maximumTeamWorkIds: dblp:journals/ijhpca/DongarraBM11
- metricVersion: researcher-metrics-window-v1@run-d7865ad3f5f8195546c1dab817328ee5
- participationCoefficient: 0.7952651700813645
- publicationCount: 80
- researcherId: scr:canon-v1.er-v2:84a0a600f9fc8efeb0a87b085e316bfd
- totalCoauthorOccurrences: 383
- weightedDegree: 383
- window: 2000-2014
- windowEndYear: 2014
- windowStartYear: 2000

## B07

Question: Have Jack J. Dongarra and Yves Robert coauthored a paper?

### Item B07-i01

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: paper-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: paper-abstract-v1
- window: all-years
Text:

> Paper: "Velosiraptor: Code Synthesis for Memory Translation." (2025), inproceedings, published in ASPLOS. Authors (5): Reto Achermann; Em Chu; Ryan Mehri; Ilias Karimalis; Margo I. Seltzer. Topics: Security and Verification in Computing.

### Item B07-i02

Evidence type: WORK_RECORD
Facts:
- title: Distributed-memory multi-GPU block-sparse tensor contraction for electronic structure.
- workId: dblp:conf/ipps/HeraultRBHLVD21
- year: 2021

### Item B07-i03

Evidence type: WORK_RECORD
Facts:
- title: A failure detector for HPC platforms.
- workId: dblp:journals/ijhpca/BosilcaBGHRSD18
- year: 2018

### Item B07-i04

Evidence type: PROFILE
Facts:
- contextFormat: semantic-context-v1
- documentType: researcher-profile
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: researcher-profile-v1
- window: all-years
Text:

> Researcher profile: Yves Robert, 2000-2026 (window all-years). Yves Robert has 216 publications in the DBLP subset in this window, first in 2000 and latest in 2026. Collaboration: 141 distinct coauthors; 691 coauthor occurrences in total; 3.20 coauthors per paper on average; at most 9 on one paper; weighted degree 691. Network position: coreness 23; a connected component of 141367 researchers, the largest. Community: a collaboration community of 20 researchers; 125 coauthors in other communities; 67 communities connected; participation coefficient 0.956. Frequent coauthors: Anne Benoit (83 joint works); Frédéric Vivien (53 joint works); Loris Marchal (33 joint works). Frequent topics: Distributed and Parallel Computing Systems (63 works); Distributed systems and fault tolerance (55 works); Parallel Computing and Optimization Techniques (42 works). Frequent venues: IPDPS (53 works); ICPP (19 works); Int. J. High Perform. Comput. Appl. (19 works). Institutions on record (any year): École Normale Supérieure de Lyon; Laboratoire de l'Informatique du Parallélisme; Institut national de recherche en sciences et technologies du numérique. Recent works: "Carbon-Aware Mapping and Scheduling for Deadline-Constrained Workflows." (2026); "Fixed-work versus fixed-time checkpointing on large-scale failure-prone platforms." (2026); "Fault-tolerant numerical iterative algorithms at scale." (2026).

### Item B07-i05

Evidence type: WORK_RECORD
Facts:
- title: Designing LU-QR Hybrid Solvers for Performance and Stability.
- workId: dblp:conf/ipps/FavergeHLLRD14
- year: 2014

### Item B07-i06

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: paper-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: paper-abstract-v1
- window: all-years
Text:

> Paper: "LSPP Introduction and Committees." (2016), inproceedings, published in IPDPS. Authors (3): Kevin J. Barker; Christopher D. Carothers; Eric Van Hensbergen.

### Item B07-i07

Evidence type: IDENTITY_RESOLUTION
Facts:
- candidateCount: 6
- decision: RESOLVED_DOMINANT
- displayName: Yves Robert
- dominantShare: 0.9
- identityConfidence: 0.9307361010669598
- minWorks: 5
- name: Yves Robert
- nameKey: name-v1:yves robert
- policyVersion: dominant-name-v1
- researcherId: scr:canon-v1.er-v2:da6e3bdff14745638f3f5425f0133388
- topShare: 0.9774774774774775
- totalWorks: 222
- works: 217

### Item B07-i08

Evidence type: WORK_RECORD
Facts:
- title: Optimal Checkpointing Period: Time vs. Energy.
- workId: dblp:conf/sc/AupyBHRD13
- year: 2013

### Item B07-i09

Evidence type: COAUTHOR_EDGE
Facts:
- displayNameA: Jack J. Dongarra
- displayNameB: Yves Robert
- edgeExists: true
- edgeId: cae-94049b45586a9d41217209d71135c559
- firstCollaborationYear: 2007
- jointPublicationCount: 23
- lastCollaborationYear: 2021
- researcherA: scr:canon-v1.er-v2:6de4012bbe8608f1d49d18cf9a40a763
- researcherB: scr:canon-v1.er-v2:da6e3bdff14745638f3f5425f0133388
- supportingWorkIds: dblp:conf/europar/AupyFRKLD13 dblp:conf/europar/BouteillerCDGHR13 dblp:conf/europar/FevreBBHHRD18 dblp:conf/ipps/BosilcaBHFRD21 dblp:conf/ipps/BosilcaBHRD14 dblp:conf/ipps/DongarraFHLR12 dblp:conf/ipps/DongarraHR13 dblp:conf/ipps/DongarraPRSV07 dblp:conf/ipps/FavergeHLLRD14 dblp:conf/ipps/FavergeLRD17 dblp:conf/ipps/HeraultRBAFBD18 dblp:conf/ipps/HeraultRBHLVD21 dblp:conf/ipps/RobertDDMSSV11 dblp:conf/ppopp/DongarraPRV08 dblp:conf/sc/AupyBHRD13 dblp:conf/sc/BosilcaBGHRSD16 dblp:conf/sc/HeraultRBD19 dblp:journals/concurrency/BosilcaBBCDGHRVZ14 dblp:journals/ijhpca/BosilcaBGHRSD18 dblp:journals/jpdc/FavergeHLLRD15 dblp:journals/pc/DongarraFHJLR13 dblp:journals/pc/FevreHRBHBD19 dblp:journals/pc/HerrmannBHMRD16
- supportingWorkIdsRef: curated/coauthor-evidence/snapshot=2026-10-03/run=run-1d6a86491812ff94887270c1ab885e69/window=all-years/cae-94049b45586a9d41217209d71135c559.json
- window: all-years
- windowEndYear: 2026
- windowStartYear: 2000

### Item B07-i10

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: paper-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: paper-abstract-v1
- window: all-years
Text:

> Paper: "Recent Advances in Parallel Virtual Machine and Message Passing Interface." (2005), article, published in Int. J. High Perform. Comput. Appl.. Authors (3): Dieter Kranzlmüller; Péter Kacsuk; Jack J. Dongarra. Topics: Distributed and Parallel Computing Systems.

### Item B07-i11

Evidence type: PROFILE
Facts:
- contextFormat: semantic-context-v1
- documentType: researcher-profile
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: researcher-profile-v1
- window: all-years
Text:

> Researcher profile: Jack J. Dongarra, 2000-2026 (window all-years). Jack J. Dongarra has 340 publications in the DBLP subset in this window, first in 2000 and latest in 2025. Collaboration: 499 distinct coauthors; 1430 coauthor occurrences in total; 4.21 coauthors per paper on average; at most 64 on one paper; weighted degree 1430. Network position: coreness 64; a connected component of 141367 researchers, the largest. Community: a collaboration community of 156 researchers; 436 coauthors in other communities; 132 communities connected; participation coefficient 0.962. Frequent coauthors: Stanimire Tomov (72 joint works); George Bosilca (67 joint works); Piotr Luszczek (54 joint works). Frequent topics: Parallel Computing and Optimization Techniques (175 works); Matrix Theory and Algorithms (66 works); Distributed and Parallel Computing Systems (43 works). Frequent venues: IPDPS (64 works); SC (56 works); Int. J. High Perform. Comput. Appl. (49 works). Institutions on record (any year): University of Tennessee at Knoxville; Oak Ridge National Laboratory; University of Manchester. Recent works: "Efficient Embedding Initialization via Dominant Eigenvector Projections." (2025); "Advancements of PAPI for the exascale generation." (2025); "Automated Data Analysis for Defining Performance Metrics from Raw Hardware Events." (2024).

### Item B07-i12

Evidence type: WORK_RECORD
Facts:
- title: Unified model for assessing checkpointing protocols at extreme-scale.
- workId: dblp:journals/concurrency/BosilcaBBCDGHRVZ14
- year: 2014

### Item B07-i13

Evidence type: WORK_RECORD
Facts:
- title: Panel Statement.
- workId: dblp:conf/ipps/RobertDDMSSV11
- year: 2011

### Item B07-i14

Evidence type: WORK_RECORD
Facts:
- title: Implementing a Systolic Algorithm for QR Factorization on Multicore Clusters with PaRSEC.
- workId: dblp:conf/europar/AupyFRKLD13
- year: 2013

### Item B07-i15

Evidence type: METRIC_RECORD
Facts:
- averageCoauthorsPerPaper: 3.199074074074074
- communitiesConnected: 67
- communityId: comm-dea1feb33fb30d9927afc3e1110c206f
- communityRunId: run-83b496169099a614ec519133077a1993
- communitySize: 20
- componentId: comp-4a11d3713f9891c5c60312810523ce70
- componentSize: 141367
- coreness: 23
- crossCommunityDegree: 125
- displayName: Yves Robert
- distinctCoauthorCount: 141
- graphDegree: 141
- maximumCoauthorsOnOnePaper: 9
- maximumTeamSize: 10
- maximumTeamWorkIds: dblp:journals/concurrency/BosilcaBBCDGHRVZ14
- metricVersion: researcher-metrics-window-v1@run-d7865ad3f5f8195546c1dab817328ee5
- participationCoefficient: 0.9555857351239877
- publicationCount: 216
- researcherId: scr:canon-v1.er-v2:da6e3bdff14745638f3f5425f0133388
- totalCoauthorOccurrences: 691
- weightedDegree: 691
- window: all-years
- windowEndYear: 2026
- windowStartYear: 2000

### Item B07-i16

Evidence type: METRIC_RECORD
Facts:
- averageCoauthorsPerPaper: 4.205882352941177
- communitiesConnected: 132
- communityId: comm-377de7bb113221b85e4f0ed00f9f60be
- communityRunId: run-83b496169099a614ec519133077a1993
- communitySize: 156
- componentId: comp-4a11d3713f9891c5c60312810523ce70
- componentSize: 141367
- coreness: 64
- crossCommunityDegree: 436
- displayName: Jack J. Dongarra
- distinctCoauthorCount: 499
- graphDegree: 499
- maximumCoauthorsOnOnePaper: 64
- maximumTeamSize: 65
- maximumTeamWorkIds: dblp:journals/ijhpca/DongarraBM11
- metricVersion: researcher-metrics-window-v1@run-d7865ad3f5f8195546c1dab817328ee5
- participationCoefficient: 0.9622451315456564
- publicationCount: 340
- researcherId: scr:canon-v1.er-v2:6de4012bbe8608f1d49d18cf9a40a763
- totalCoauthorOccurrences: 1430
- weightedDegree: 1430
- window: all-years
- windowEndYear: 2026
- windowStartYear: 2000

### Item B07-i17

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: paper-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: paper-abstract-v1
- window: all-years
Text:

> Paper: "Dynostore: A Wide-Area Distribution System for the Management of Data Over Heterogeneous Storage." (2025), inproceedings, published in CCGRID. Authors (9): Dante D. Sánchez-Gallegos; José Luis González Compeán; Maxime Gonthier; Valérie Hayot-Sasson; J. Gregory Pauloski; Haochen Pan; Kyle Chard; Jesús Carretero 0001; Ian T. Foster. Topics: Advanced Data Storage Technologies.

### Item B07-i18

Evidence type: WORK_RECORD
Facts:
- title: Assessing the Impact of ABFT and Checkpoint Composite Strategies.
- workId: dblp:conf/ipps/BosilcaBHRD14
- year: 2014

### Item B07-i19

Evidence type: WORK_RECORD
Facts:
- title: Hierarchical QR Factorization Algorithms for Multi-core Cluster Systems.
- workId: dblp:conf/ipps/DongarraFHLR12
- year: 2012

### Item B07-i20

Evidence type: WORK_RECORD
Facts:
- title: Mixing LU and QR factorization algorithms to design high-performance dense linear algebra solvers.
- workId: dblp:journals/jpdc/FavergeHLLRD15
- year: 2015

### Item B07-i21

Evidence type: IDENTITY_RESOLUTION
Facts:
- candidateCount: 17
- decision: RESOLVED_DOMINANT
- displayName: Jack J. Dongarra
- dominantShare: 0.9
- identityConfidence: 0.9300527553067827
- minWorks: 5
- name: Jack J. Dongarra
- nameKey: name-v1:jack j dongarra
- policyVersion: dominant-name-v1
- researcherId: scr:canon-v1.er-v2:6de4012bbe8608f1d49d18cf9a40a763
- topShare: 0.9575596816976127
- totalWorks: 377
- works: 361

### Item B07-i22

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: paper-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: paper-abstract-v1
- window: all-years
Text:

> Paper: "Implementing a Systolic Algorithm for QR Factorization on Multicore Clusters with PaRSEC." (2013), inproceedings, published in Euro-Par. Authors (6): Guillaume Aupy; Mathieu Faverge; Yves Robert; Jakub Kurzak; Piotr Luszczek; Jack J. Dongarra. Topics: Parallel Computing and Optimization Techniques.

### Item B07-i23

Evidence type: WORK_RECORD
Facts:
- title: Revisiting Credit Distribution Algorithms for Distributed Termination Detection.
- workId: dblp:conf/ipps/BosilcaBHFRD21
- year: 2021

### Item B07-i24

Evidence type: WORK_RECORD
Facts:
- title: Optimal Cooperative Checkpointing for Shared High-Performance Computing Platforms.
- workId: dblp:conf/ipps/HeraultRBAFBD18
- year: 2018

### Item B07-i25

Evidence type: WORK_RECORD
Facts:
- title: Revisiting Matrix Product on Master-Worker Platforms.
- workId: dblp:conf/ipps/DongarraPRSV07
- year: 2007

### Item B07-i26

Evidence type: WORK_RECORD
Facts:
- title: Comparing the performance of rigid, moldable and grid-shaped applications on failure-prone HPC platforms.
- workId: dblp:journals/pc/FevreHRBHBD19
- year: 2019

### Item B07-i27

Evidence type: WORK_RECORD
Facts:
- title: Assessing the cost of redistribution followed by a computational kernel: Complexity and performance results.
- workId: dblp:journals/pc/HerrmannBHMRD16
- year: 2016

### Item B07-i28

Evidence type: WORK_RECORD
Facts:
- title: Matrix product on heterogeneous master-worker platforms.
- workId: dblp:conf/ppopp/DongarraPRV08
- year: 2008

### Item B07-i29

Evidence type: WORK_RECORD
Facts:
- title: Revisiting the Double Checkpointing Algorithm.
- workId: dblp:conf/ipps/DongarraHR13
- year: 2013

### Item B07-i30

Evidence type: WORK_RECORD
Facts:
- title: Failure detection and propagation in HPC systems.
- workId: dblp:conf/sc/BosilcaBGHRSD16
- year: 2016

### Item B07-i31

Evidence type: WORK_RECORD
Facts:
- title: Multi-criteria Checkpointing Strategies: Response-Time versus Resource Utilization.
- workId: dblp:conf/europar/BouteillerCDGHR13
- year: 2013

### Item B07-i32

Evidence type: WORK_RECORD
Facts:
- title: Bidiagonalization and R-Bidiagonalization: Parallel Tiled Algorithms, Critical Paths and Distributed-Memory Implementation.
- workId: dblp:conf/ipps/FavergeLRD17
- year: 2017

### Item B07-i33

Evidence type: WORK_RECORD
Facts:
- title: Do Moldable Applications Perform Better on Failure-Prone HPC Platforms?
- workId: dblp:conf/europar/FevreBBHHRD18
- year: 2018

### Item B07-i34

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: paper-summary
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: paper-abstract-v1
- window: all-years
Text:

> Paper: "DOTA: Intelligent Debugging with Delta of Thoughts Agents." (2026), article, published in ACM SIGOPS Oper. Syst. Rev.. Authors (4): Rui Yang; Rajiv Gupta 0001; Qian Zhang 0020; Ashish Kundu. Topics: Software Testing and Debugging Techniques.

### Item B07-i35

Evidence type: WORK_RECORD
Facts:
- title: Generic Matrix Multiplication for Multi-GPU Accelerated Distributed-Memory Platforms over PaRSEC.
- workId: dblp:conf/sc/HeraultRBD19
- year: 2019

### Item B07-i36

Evidence type: WORK_RECORD
Facts:
- title: Hierarchical QR factorization algorithms for multi-core clusters.
- workId: dblp:journals/pc/DongarraFHJLR13
- year: 2013

## B08

Question: How many joint papers do Rajkumar Buyya and Albert Y. Zomaya have, and when did they first collaborate?

### Item B08-i01

Evidence type: IDENTITY_RESOLUTION
Facts:
- candidateCount: 11
- decision: RESOLVED_DOMINANT
- displayName: Rajkumar Buyya
- dominantShare: 0.9
- identityConfidence: 0.9300527553067827
- minWorks: 5
- name: Rajkumar Buyya
- nameKey: name-v1:rajkumar buyya
- policyVersion: dominant-name-v1
- researcherId: scr:canon-v1.er-v2:bede83f65bedfab8f7fc802f295ee293
- topShare: 0.964516129032258
- totalWorks: 310
- works: 299

### Item B08-i02

Evidence type: COAUTHOR_EDGE
Facts:
- displayNameA: Albert Y. Zomaya
- displayNameB: Rajkumar Buyya
- edgeExists: true
- edgeId: cae-d65f27c5a5fd6a6257e31f81d3eabf7b
- firstCollaborationYear: 2020
- jointPublicationCount: 3
- lastCollaborationYear: 2023
- researcherA: scr:canon-v1.er-v2:9c35232fa374c48fdc67309bfedd5f7c
- researcherB: scr:canon-v1.er-v2:bede83f65bedfab8f7fc802f295ee293
- supportingWorkIds: dblp:journals/jpdc/HsiehLBZ20 dblp:journals/tcc/ChenHHBZ20 dblp:journals/tcc/ChungLKHZB23
- window: all-years
- windowEndYear: 2026
- windowStartYear: 2000

### Item B08-i03

Evidence type: METRIC_RECORD
Facts:
- averageCoauthorsPerPaper: 2.7235494880546076
- communitiesConnected: 184
- communityId: comm-5ca23f9407a9f8f50a7b4820f5e924e2
- communityRunId: run-83b496169099a614ec519133077a1993
- communitySize: 617
- componentId: comp-4a11d3713f9891c5c60312810523ce70
- componentSize: 141367
- coreness: 17
- crossCommunityDegree: 362
- displayName: Rajkumar Buyya
- distinctCoauthorCount: 419
- graphDegree: 419
- maximumCoauthorsOnOnePaper: 7
- maximumTeamSize: 8
- maximumTeamWorkIds: dblp:journals/concurrency/VermaGNRIRCB16 dblp:journals/fgcs/MangalaRARBVIP26 dblp:journals/jpdc/LiASPJBMR19 dblp:journals/tpds/ZhouFLXTBQS25
- metricVersion: researcher-metrics-window-v1@run-d7865ad3f5f8195546c1dab817328ee5
- participationCoefficient: 0.9671851948895256
- publicationCount: 293
- researcherId: scr:canon-v1.er-v2:bede83f65bedfab8f7fc802f295ee293
- totalCoauthorOccurrences: 798
- weightedDegree: 798
- window: all-years
- windowEndYear: 2026
- windowStartYear: 2000

### Item B08-i04

Evidence type: METRIC_RECORD
Facts:
- averageCoauthorsPerPaper: 3.620805369127517
- communitiesConnected: 153
- communityId: comm-722998d6fb1938cf415ae2f06729d4ff
- communityRunId: run-83b496169099a614ec519133077a1993
- communitySize: 118
- componentId: comp-4a11d3713f9891c5c60312810523ce70
- componentSize: 141367
- coreness: 22
- crossCommunityDegree: 443
- displayName: Albert Y. Zomaya
- distinctCoauthorCount: 525
- graphDegree: 525
- maximumCoauthorsOnOnePaper: 22
- maximumTeamSize: 23
- maximumTeamWorkIds: dblp:journals/pc/HussainMHKBMQZWGKZXBVPPKBLWCR13
- metricVersion: researcher-metrics-window-v1@run-d7865ad3f5f8195546c1dab817328ee5
- participationCoefficient: 0.9479183673469388
- publicationCount: 298
- researcherId: scr:canon-v1.er-v2:9c35232fa374c48fdc67309bfedd5f7c
- totalCoauthorOccurrences: 1079
- weightedDegree: 1079
- window: all-years
- windowEndYear: 2026
- windowStartYear: 2000

### Item B08-i05

Evidence type: PROFILE
Facts:
- contextFormat: semantic-context-v1
- documentType: researcher-profile
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: researcher-profile-v1
- window: all-years
Text:

> Researcher profile: Rajkumar Buyya, 2000-2026 (window all-years). Rajkumar Buyya has 293 publications in the DBLP subset in this window, first in 2000 and latest in 2026. Collaboration: 419 distinct coauthors; 798 coauthor occurrences in total; 2.72 coauthors per paper on average; at most 7 on one paper; weighted degree 798. Network position: coreness 17; a connected component of 141367 researchers, the largest. Community: a collaboration community of 617 researchers; 362 coauthors in other communities; 184 communities connected; participation coefficient 0.967. Frequent coauthors: Rajiv Ranjan 0001 (20 joint works); Rodrigo N. Calheiros (20 joint works); Dawei Sun 0001 (17 joint works). Frequent topics: Cloud Computing and Resource Management (132 works); Distributed and Parallel Computing Systems (71 works); IoT and Edge/Fog Computing (22 works). Frequent venues: Future Gener. Comput. Syst. (77 works); Concurr. Comput. Pract. Exp. (40 works); CCGRID (33 works). Institutions on record (any year): The University of Melbourne; Monash University; The University of Melbourne, Australia. Recent works: "REACH: Reinforcement Learning for Adaptive Microservice Rescheduling in the Cloud-Edge Continuum." (2026); "Coded Redundancy for Reducing Task Average Latency in Distributed Stream Processing Systems." (2026); "Predictscale: Proactive Autoscaling Via Workload Forecasting for Edge Microservices Application." (2026).

### Item B08-i06

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: collaboration-path
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: collaboration-path-v1
- window: all-years
Text:

> Collaboration path, 2000-2026 (window all-years), between Rajkumar Buyya and Albert Y. Zomaya: a shortest coauthor path of 1 hop. Route: Rajkumar Buyya -> Albert Y. Zomaya (3 joint works, 2020-2023, for example "Utilization-prediction-aware virtual machine consolidation approach for energy-efficient cloud data centers.").

### Item B08-i07

Evidence type: PROFILE
Facts:
- contextFormat: semantic-context-v1
- documentType: researcher-profile
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: researcher-profile-v1
- window: all-years
Text:

> Researcher profile: Albert Y. Zomaya, 2000-2026 (window all-years). Albert Y. Zomaya has 298 publications in the DBLP subset in this window, first in 2000 and latest in 2026. Collaboration: 525 distinct coauthors; 1079 coauthor occurrences in total; 3.62 coauthors per paper on average; at most 22 on one paper; weighted degree 1079. Network position: coreness 22; a connected component of 141367 researchers, the largest. Community: a collaboration community of 118 researchers; 443 coauthors in other communities; 153 communities connected; participation coefficient 0.948. Frequent coauthors: Young Choon Lee (43 joint works); Rajiv Ranjan 0001 (28 joint works); Zahir Tari (27 joint works). Frequent topics: Cloud Computing and Resource Management (79 works); Distributed and Parallel Computing Systems (32 works); Parallel Computing and Optimization Techniques (18 works). Frequent venues: IEEE Trans. Parallel Distributed Syst. (73 works); J. Parallel Distributed Comput. (46 works); Future Gener. Comput. Syst. (29 works). Institutions on record (any year): The University of Sydney; The University of Western Australia; School of Computer Science, University of Sydney, Sydney, NSW, Australia. Recent works: "GPU-Accelerated Approximate Nearest Neighbor Search via PCA-Augmented Graph Indexing for Vector Databases." (2026); "Optimal schedule for periodic jobs with discretely controllable processing times on two machines." (2026); "DAHFF: Joint Device Selection and Bandwidth Allocation for Efficient Hierarchical Federated Learning." (2026).

### Item B08-i08

Evidence type: GRAPH_PATH
Facts:
- hop.1.edgeId: cae-d65f27c5a5fd6a6257e31f81d3eabf7b
- hop.1.firstYear: 2020
- hop.1.from: scr:canon-v1.er-v2:bede83f65bedfab8f7fc802f295ee293
- hop.1.fromName: Rajkumar Buyya
- hop.1.jointPublicationCount: 3
- hop.1.lastYear: 2023
- hop.1.to: scr:canon-v1.er-v2:9c35232fa374c48fdc67309bfedd5f7c
- hop.1.toName: Albert Y. Zomaya
- maxDepth: 6
- pathLength: 1
- researcherIds: scr:canon-v1.er-v2:bede83f65bedfab8f7fc802f295ee293 scr:canon-v1.er-v2:9c35232fa374c48fdc67309bfedd5f7c
- source: scr:canon-v1.er-v2:bede83f65bedfab8f7fc802f295ee293
- sourceName: Rajkumar Buyya
- status: FOUND
- target: scr:canon-v1.er-v2:9c35232fa374c48fdc67309bfedd5f7c
- targetName: Albert Y. Zomaya
- window: all-years
- windowEndYear: 2026
- windowStartYear: 2000

### Item B08-i09

Evidence type: IDENTITY_RESOLUTION
Facts:
- candidateCount: 4
- decision: RESOLVED_DOMINANT
- displayName: Albert Y. Zomaya
- dominantShare: 0.9
- identityConfidence: 0.9300527553067827
- minWorks: 5
- name: Albert Y. Zomaya
- nameKey: name-v1:albert y zomaya
- policyVersion: dominant-name-v1
- researcherId: scr:canon-v1.er-v2:9c35232fa374c48fdc67309bfedd5f7c
- topShare: 0.9901639344262295
- totalWorks: 305
- works: 302

### Item B08-i10

Evidence type: WORK_RECORD
Facts:
- title: Heterogeneous Job Allocation Scheduler for Hadoop MapReduce Using Dynamic Grouping Integrated Neighboring Search.
- workId: dblp:journals/tcc/ChenHHBZ20
- year: 2020

### Item B08-i11

Evidence type: WORK_RECORD
Facts:
- title: Utilization-prediction-aware virtual machine consolidation approach for energy-efficient cloud data centers.
- workId: dblp:journals/jpdc/HsiehLBZ20
- year: 2020

### Item B08-i12

Evidence type: WORK_RECORD
Facts:
- title: Dynamic Parallel Flow Algorithms With Centralized Scheduling for Load Balancing in Cloud Data Center Networks.
- workId: dblp:journals/tcc/ChungLKHZB23
- year: 2023

## B09

Question: Have Lei Zhang and Wei Wang coauthored a paper?

(no item for this question)

## B10

Question: What is the shortest coauthorship path between Rajkumar Buyya and Rachid Guerraoui?

### Item B10-i01

Evidence type: WORK_RECORD
Facts:
- title: ProteusTM: Abstraction Meets Performance in Transactional Memory.
- workId: dblp:conf/asplos/DidonaDKGNR16
- year: 2016

### Item B10-i02

Evidence type: METRIC_RECORD
Facts:
- averageCoauthorsPerPaper: 2.9393939393939394
- communitiesConnected: 48
- communityId: comm-b7d5d6a31f74b68d124c7c88494cdf2b
- communityRunId: run-83b496169099a614ec519133077a1993
- communitySize: 621
- componentId: comp-4a11d3713f9891c5c60312810523ce70
- componentSize: 141367
- coreness: 12
- crossCommunityDegree: 84
- displayName: Rachid Guerraoui
- distinctCoauthorCount: 231
- graphDegree: 231
- maximumCoauthorsOnOnePaper: 9
- maximumTeamSize: 10
- maximumTeamWorkIds: dblp:conf/dsn/CollinsGKKMPPST20
- metricVersion: researcher-metrics-window-v1@run-d7865ad3f5f8195546c1dab817328ee5
- participationCoefficient: 0.5875826914787954
- publicationCount: 231
- researcherId: scr:canon-v1.er-v2:ba10a85f922c075c3cfdbf5a040332b8
- totalCoauthorOccurrences: 679
- weightedDegree: 679
- window: all-years
- windowEndYear: 2026
- windowStartYear: 2000

### Item B10-i03

Evidence type: COAUTHOR_EDGE
Facts:
- displayNameA: Paolo Romano 0002
- displayNameB: Rachid Guerraoui
- edgeExists: true
- edgeId: cae-79a866678d54d577129a2dcafc1433a8
- firstCollaborationYear: 2016
- jointPublicationCount: 1
- lastCollaborationYear: 2016
- researcherA: scr:canon-v1.er-v2:26367611b6aa01309f3b27e3bbc344bb
- researcherB: scr:canon-v1.er-v2:ba10a85f922c075c3cfdbf5a040332b8
- supportingWorkIds: dblp:conf/asplos/DidonaDKGNR16
- window: all-years
- windowEndYear: 2026
- windowStartYear: 2000

### Item B10-i04

Evidence type: TEXT_CHUNK
Facts:
- contextFormat: semantic-context-v1
- documentType: collaboration-path
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: collaboration-path-v1
- window: all-years
Text:

> Collaboration path, 2000-2026 (window all-years), between Rajkumar Buyya and Rachid Guerraoui: a shortest coauthor path of 3 hops. Route: Rajkumar Buyya -> Ivona Brandic (3 joint works, 2009-2012, for example "Special section: Recent advances in utility and cloud computing.") -> Paolo Romano 0002 (1 joint work, in 2012, for example "Topic 6: Grid, Cluster and Cloud Computing.") -> Rachid Guerraoui (1 joint work, in 2016, for example "ProteusTM: Abstraction Meets Performance in Transactional Memory.").

### Item B10-i05

Evidence type: COAUTHOR_EDGE
Facts:
- displayNameA: Ivona Brandic
- displayNameB: Rajkumar Buyya
- edgeExists: true
- edgeId: cae-d9861ae25d8627ccb7de118f6d3228b6
- firstCollaborationYear: 2009
- jointPublicationCount: 3
- lastCollaborationYear: 2012
- researcherA: scr:canon-v1.er-v2:579c60649a1eaa5276061729c159fa5d
- researcherB: scr:canon-v1.er-v2:bede83f65bedfab8f7fc802f295ee293
- supportingWorkIds: dblp:journals/fgcs/BrandicB12 dblp:journals/fgcs/BuyyaYVBB09 dblp:journals/fgcs/EmeakarohaNCBBR12
- window: all-years
- windowEndYear: 2026
- windowStartYear: 2000

### Item B10-i06

Evidence type: WORK_RECORD
Facts:
- title: Topic 6: Grid, Cluster and Cloud Computing.
- workId: dblp:conf/europar/ElmrothFABDR12
- year: 2012

### Item B10-i07

Evidence type: GRAPH_PATH
Facts:
- hop.1.edgeId: cae-d9861ae25d8627ccb7de118f6d3228b6
- hop.1.firstYear: 2009
- hop.1.from: scr:canon-v1.er-v2:bede83f65bedfab8f7fc802f295ee293
- hop.1.fromName: Rajkumar Buyya
- hop.1.jointPublicationCount: 3
- hop.1.lastYear: 2012
- hop.1.to: scr:canon-v1.er-v2:579c60649a1eaa5276061729c159fa5d
- hop.1.toName: Ivona Brandic
- hop.2.edgeId: cae-05ac04e43d13eeb9fcdf2dffc24c67f8
- hop.2.firstYear: 2012
- hop.2.from: scr:canon-v1.er-v2:579c60649a1eaa5276061729c159fa5d
- hop.2.fromName: Ivona Brandic
- hop.2.jointPublicationCount: 1
- hop.2.lastYear: 2012
- hop.2.to: scr:canon-v1.er-v2:26367611b6aa01309f3b27e3bbc344bb
- hop.2.toName: Paolo Romano 0002
- hop.3.edgeId: cae-79a866678d54d577129a2dcafc1433a8
- hop.3.firstYear: 2016
- hop.3.from: scr:canon-v1.er-v2:26367611b6aa01309f3b27e3bbc344bb
- hop.3.fromName: Paolo Romano 0002
- hop.3.jointPublicationCount: 1
- hop.3.lastYear: 2016
- hop.3.to: scr:canon-v1.er-v2:ba10a85f922c075c3cfdbf5a040332b8
- hop.3.toName: Rachid Guerraoui
- maxDepth: 6
- pathLength: 3
- researcherIds: scr:canon-v1.er-v2:bede83f65bedfab8f7fc802f295ee293 scr:canon-v1.er-v2:579c60649a1eaa5276061729c159fa5d scr:canon-v1.er-v2:26367611b6aa01309f3b27e3bbc344bb scr:canon-v1.er-v2:ba10a85f922c075c3cfdbf5a040332b8
- source: scr:canon-v1.er-v2:bede83f65bedfab8f7fc802f295ee293
- sourceName: Rajkumar Buyya
- status: FOUND
- target: scr:canon-v1.er-v2:ba10a85f922c075c3cfdbf5a040332b8
- targetName: Rachid Guerraoui
- window: all-years
- windowEndYear: 2026
- windowStartYear: 2000

### Item B10-i08

Evidence type: PROFILE
Facts:
- contextFormat: semantic-context-v1
- documentType: researcher-profile
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: researcher-profile-v1
- window: all-years
Text:

> Researcher profile: Rachid Guerraoui, 2000-2026 (window all-years). Rachid Guerraoui has 231 publications in the DBLP subset in this window, first in 2000 and latest in 2026. Collaboration: 231 distinct coauthors; 679 coauthor occurrences in total; 2.94 coauthors per paper on average; at most 9 on one paper; weighted degree 679. Network position: coreness 12; a connected component of 141367 researchers, the largest. Community: a collaboration community of 621 researchers; 84 coauthors in other communities; 48 communities connected; participation coefficient 0.588. Frequent coauthors: Anne-Marie Kermarrec (31 joint works); Seth Gilbert (22 joint works); Vincent Gramoli (17 joint works). Frequent topics: Distributed systems and fault tolerance (153 works); Peer-to-Peer Network Technologies (9 works); Privacy-Preserving Technologies in Data (9 works). Frequent venues: PODC (38 works); DISC (35 works); Distributed Comput. (21 works). Institutions on record (any year): École Polytechnique Fédérale de Lausanne; EPFL, Lausanne, Switzerland; EPFL. Recent works: "SpectralShard: Lightweight Partitioning Under Dynamic Interaction Patterns." (2026); "Evaluating Blockchain Fault Tolerance with Stabl." (2025); "Stabl: The Sensitivity of Blockchains to Failures." (2025).

### Item B10-i09

Evidence type: WORK_RECORD
Facts:
- title: Cloud computing and emerging IT platforms: Vision, hype, and reality for delivering computing as the 5th utility.
- workId: dblp:journals/fgcs/BuyyaYVBB09
- year: 2009

### Item B10-i10

Evidence type: IDENTITY_RESOLUTION
Facts:
- candidateCount: 11
- decision: RESOLVED_DOMINANT
- displayName: Rachid Guerraoui
- dominantShare: 0.9
- identityConfidence: 0.9300527553067827
- minWorks: 5
- name: Rachid Guerraoui
- nameKey: name-v1:rachid guerraoui
- policyVersion: dominant-name-v1
- researcherId: scr:canon-v1.er-v2:ba10a85f922c075c3cfdbf5a040332b8
- topShare: 0.9615384615384616
- totalWorks: 260
- works: 250

### Item B10-i11

Evidence type: WORK_RECORD
Facts:
- title: Towards autonomic detection of SLA violations in Cloud infrastructures.
- workId: dblp:journals/fgcs/EmeakarohaNCBBR12
- year: 2012

### Item B10-i12

Evidence type: WORK_RECORD
Facts:
- title: Special section: Recent advances in utility and cloud computing.
- workId: dblp:journals/fgcs/BrandicB12
- year: 2012

### Item B10-i13

Evidence type: PROFILE
Facts:
- contextFormat: semantic-context-v1
- documentType: researcher-profile
- embeddingModelVersion: amazon.titan-embed-text-v2:0|1024|normalized|rendering-v1
- index: researcher-profile-v1
- window: all-years
Text:

> Researcher profile: Rajkumar Buyya, 2000-2026 (window all-years). Rajkumar Buyya has 293 publications in the DBLP subset in this window, first in 2000 and latest in 2026. Collaboration: 419 distinct coauthors; 798 coauthor occurrences in total; 2.72 coauthors per paper on average; at most 7 on one paper; weighted degree 798. Network position: coreness 17; a connected component of 141367 researchers, the largest. Community: a collaboration community of 617 researchers; 362 coauthors in other communities; 184 communities connected; participation coefficient 0.967. Frequent coauthors: Rajiv Ranjan 0001 (20 joint works); Rodrigo N. Calheiros (20 joint works); Dawei Sun 0001 (17 joint works). Frequent topics: Cloud Computing and Resource Management (132 works); Distributed and Parallel Computing Systems (71 works); IoT and Edge/Fog Computing (22 works). Frequent venues: Future Gener. Comput. Syst. (77 works); Concurr. Comput. Pract. Exp. (40 works); CCGRID (33 works). Institutions on record (any year): The University of Melbourne; Monash University; The University of Melbourne, Australia. Recent works: "REACH: Reinforcement Learning for Adaptive Microservice Rescheduling in the Cloud-Edge Continuum." (2026); "Coded Redundancy for Reducing Task Average Latency in Distributed Stream Processing Systems." (2026); "Predictscale: Proactive Autoscaling Via Workload Forecasting for Edge Microservices Application." (2026).

### Item B10-i14

Evidence type: IDENTITY_RESOLUTION
Facts:
- candidateCount: 11
- decision: RESOLVED_DOMINANT
- displayName: Rajkumar Buyya
- dominantShare: 0.9
- identityConfidence: 0.9300527553067827
- minWorks: 5
- name: Rajkumar Buyya
- nameKey: name-v1:rajkumar buyya
- policyVersion: dominant-name-v1
- researcherId: scr:canon-v1.er-v2:bede83f65bedfab8f7fc802f295ee293
- topShare: 0.964516129032258
- totalWorks: 310
- works: 299

### Item B10-i15

Evidence type: METRIC_RECORD
Facts:
- averageCoauthorsPerPaper: 2.7235494880546076
- communitiesConnected: 184
- communityId: comm-5ca23f9407a9f8f50a7b4820f5e924e2
- communityRunId: run-83b496169099a614ec519133077a1993
- communitySize: 617
- componentId: comp-4a11d3713f9891c5c60312810523ce70
- componentSize: 141367
- coreness: 17
- crossCommunityDegree: 362
- displayName: Rajkumar Buyya
- distinctCoauthorCount: 419
- graphDegree: 419
- maximumCoauthorsOnOnePaper: 7
- maximumTeamSize: 8
- maximumTeamWorkIds: dblp:journals/concurrency/VermaGNRIRCB16 dblp:journals/fgcs/MangalaRARBVIP26 dblp:journals/jpdc/LiASPJBMR19 dblp:journals/tpds/ZhouFLXTBQS25
- metricVersion: researcher-metrics-window-v1@run-d7865ad3f5f8195546c1dab817328ee5
- participationCoefficient: 0.9671851948895256
- publicationCount: 293
- researcherId: scr:canon-v1.er-v2:bede83f65bedfab8f7fc802f295ee293
- totalCoauthorOccurrences: 798
- weightedDegree: 798
- window: all-years
- windowEndYear: 2026
- windowStartYear: 2000

### Item B10-i16

Evidence type: COAUTHOR_EDGE
Facts:
- displayNameA: Paolo Romano 0002
- displayNameB: Ivona Brandic
- edgeExists: true
- edgeId: cae-05ac04e43d13eeb9fcdf2dffc24c67f8
- firstCollaborationYear: 2012
- jointPublicationCount: 1
- lastCollaborationYear: 2012
- researcherA: scr:canon-v1.er-v2:26367611b6aa01309f3b27e3bbc344bb
- researcherB: scr:canon-v1.er-v2:579c60649a1eaa5276061729c159fa5d
- supportingWorkIds: dblp:conf/europar/ElmrothFABDR12
- window: all-years
- windowEndYear: 2026
- windowStartYear: 2000
