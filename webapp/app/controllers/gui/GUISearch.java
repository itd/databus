package controllers.gui;

import gov.nrel.util.Utility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import models.EntityUser;

import org.codehaus.jackson.map.ObjectMapper;

import play.mvc.Controller;
import play.mvc.Http.Request;
import play.mvc.With;

import com.alvazan.orm.api.base.NoSqlEntityManager;
import com.alvazan.orm.api.base.NoSqlEntityManagerFactory;
import com.alvazan.play.NoSql;

import controllers.Search;
import controllers.gui.auth.GuiSecure;
import controllers.gui.solrsearch.SearchItem;
import controllers.gui.solrsearch.SearchableItems;
import controllers.gui.solrsearch.SolrSearchResult;

@With(GuiSecure.class)
public class GUISearch extends Controller {
	private static Map<String, Map<String, List<SearchItem>>> userIndexMapCache = new ConcurrentHashMap<String, Map<String, List<SearchItem>>>();
	
	public static void legacyMetaSearch() {
		EntityUser user = Utility.getCurrentUser(session);
		render(user);
	}
	
	public static void initiateIndexSearch() {
		/*
		String solrURL = Utility.getSolrServer();
		renderArgs.put("solrURL", solrURL);
		
		EntityUser user = Utility.getCurrentUser(session);
		render(user);
		*/
		
		/**
		 * Get a full listing of all available indexes and then return them as a list of clickable
		 * links that then drill down to a search page for that specific index.
		 * 
		 * Since the current searchableItems call is so slow, we'll hit it and cache the results
		 * in a user cache mapping.
		 */
		EntityUser user = Utility.getCurrentUser(session);
		
		if(!GUISearch.userIndexMapCache.containsKey(user.getId())) {
			GUISearch.userIndexMapCache.put(user.getId(), GUISearch.getSearchableItems());
		}
		
		Map<String, List<SearchItem>> searchableItems = GUISearch.userIndexMapCache.get(user.getId());
		List<String> indexes = new ArrayList<String>(searchableItems.keySet());
		java.util.Collections.sort(indexes);
		
		System.out.println("Recieved " + indexes.size() + " indices");
		
		render(indexes);
	}
	
	public static void indexSearch(String index, String query) {
		System.out.println("GUISearch.indexSearch() - index:[" + index + "], query:[" + query + "]");
		
		renderArgs.put("searchString", query);
		renderArgs.put("searchTable", index);
		
		String solrURL = Utility.getSolrServer();
		renderArgs.put("solrURL", solrURL);
		
		EntityUser user = Utility.getCurrentUser(session);
		render(user);	
	}
	
	private static Map<String, List<SearchItem>> getSearchableItems() {
		Map<String, List<SearchItem>> items = new ConcurrentHashMap<String,List<SearchItem>>();
		
		String itemResults = Search.getSearchableItems();
		//String itemResults = "{\"searchableItems\":[{\"db\": \"modbus\",\"id\": \"modbusdeviceMeta\",\"type\":\"table\"},{\"db\": \"modbus\",\"id\": \"modbusstreamMeta\",\"type\":\"table\"},{\"db\": \"bacnet\",\"id\": \"bacnetdeviceMeta\",\"type\":\"table\"},{\"db\": \"bacnet\",\"id\": \"bacnetstreamMeta\",\"type\":\"table\"},{\"db\": \"databusmeta\",\"id\": \"databusmeta\",\"type\":\"meta\"}]}";
		
		ObjectMapper mapper = new ObjectMapper();
		SearchableItems searchableItems = null;
		try {
			searchableItems = mapper.readValue(itemResults, SearchableItems.class);
		} catch (Exception e) {
			// Got an error, make the list empty
			e.printStackTrace();
			return items;
		}
		
		List<SearchItem> facetItems = searchableItems.getSearchableItems();
		for(SearchItem item : facetItems) {
			String db = item.getDb();
			
			if(items.containsKey(db)) {
				items.get(db).add(item);
			} else {
				List<SearchItem> dbList = new ArrayList<SearchItem>();
				dbList.add(item);
				items.put(db, dbList);
			}
		}
		
		// DEBUG
		System.out.println("SearchableItems:\n");
		for (Map.Entry<String, List<SearchItem>> entry : items.entrySet()) {
		    String db = entry.getKey();
		    List<SearchItem> dbEntries = entry.getValue();
		    
		    System.out.println("\n");
		    System.out.println("\t" + db.toUpperCase());
		    
		    for(SearchItem item : dbEntries) {
		    		System.out.println("\t\tNAME: " + item.getId());
		    		System.out.println("\t\tTYPE: " + item.getType() + "\n");
		    }
		}
		
		return items;
	}
	
	public static void metaSearch() {
		String solrURL = Utility.getSolrServer();
		renderArgs.put("solrURL", solrURL);
		
		String searchString = params.get("searchString");
		if((searchString != null) && (!searchString.equals(""))) {
			renderArgs.put("_globalMetaSearch", true);
			renderArgs.put("_searchString", searchString);
		} else {
			renderArgs.put("_globalMetaSearch", false);
		}
		
		EntityUser user = Utility.getCurrentUser(session);
		render(user);
	}
	
	public static void detailSearch(String searchTable, String searchString) {
		//System.out.println("\n\n\n\nDETAIL SEARCH STRING: " + searchString + " - TABLE: " + searchTable + "\n\n\n\n");
		
		renderArgs.put("searchString", searchString);
		renderArgs.put("searchTable", searchTable);
		
		String solrURL = Utility.getSolrServer();
		renderArgs.put("solrURL", solrURL);
		
		EntityUser user = Utility.getCurrentUser(session);
		render(user);		
	}
	
	public static void globalSearch() {
		String searchString = params.get("searchString");
		
		renderArgs.put("searchString", searchString);
		
		/**
		 * Global Search is a combination between MetaSearch and Detail Search... but with a twist.
		 * 
		 * The following are the steps that are required in order to perform a global search:
		 * 
		 * 		1) FInd out everything we can search via the solr "searchableItems" call.
		 * 				- 	the solr "searchableItems" call is NOT a solr call but a DB call.  The results
		 * 					of this call return all tables and "items" within cassandra that are labled 
		 * 					"is searchable" in their definition.
		 * 		2) For each result from the "searchableItems" call, we will then do a solr search using
		 * 					the passed in search term.
		 * 		3) Going through each result, we will order the searches by total number of "hits".
		 * 		4) Returning to the user will use the "Meta Search" interface but the facets on the left
		 * 			will be the items returned by the "searchableItems" call and the page results will
		 * 			be the solr results from the highest "hit" search found.
		 */
		
		/*
		 * Step 1 - Call Search.getSearchableItems() and parse the results
		 */
		String itemResults = Search.getSearchableItems();
		//String itemResults = "{\"searchableItems\":[{\"db\": \"modbus\",\"id\": \"modbusdeviceMeta\",\"type\":\"table\"},{\"db\": \"modbus\",\"id\": \"modbusstreamMeta\",\"type\":\"table\"},{\"db\": \"bacnet\",\"id\": \"bacnetdeviceMeta\",\"type\":\"table\"},{\"db\": \"bacnet\",\"id\": \"bacnetstreamMeta\",\"type\":\"table\"},{\"db\": \"databusmeta\",\"id\": \"databusmeta\",\"type\":\"meta\"}]}";
		
		ObjectMapper mapper = new ObjectMapper();
		SearchableItems searchableItems = null;
		try {
			searchableItems = mapper.readValue(itemResults, SearchableItems.class);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			
			// Regardless of what "actually" happened, we got an error, just put it in the output
			renderArgs.put("searchResultStatus", false);
			render();
		}
		
		Map<String, List<SearchItem>> facets = new ConcurrentHashMap<String,List<SearchItem>>();
		
		List<SearchItem> facetItems = searchableItems.getSearchableItems();
		for(SearchItem item : facetItems) {
			String db = item.getDb();
			
			if(facets.containsKey(db)) {
				facets.get(db).add(item);
			} else {
				List<SearchItem> dbList = new ArrayList<SearchItem>();
				dbList.add(item);
				facets.put(db, dbList);
			}
		}
		
		// DEBUG vv
		System.out.println("\n\n\n\nGLOBAL SEARCH STRING: " + searchString);
		System.out.println("\n\n");
		for (Map.Entry<String, List<SearchItem>> entry : facets.entrySet()) {
		    String db = entry.getKey();
		    List<SearchItem> dbEntries = entry.getValue();
		    
		    System.out.println("\n");
		    System.out.println("\t" + db.toUpperCase());
		    
		    for(SearchItem item : dbEntries) {
		    		System.out.println("\t\tNAME: " + item.getId());
		    		System.out.println("\t\tTYPE: " + item.getType() + "\n");
		    }
		}
		// DEBUG ^^
		
		/*
		 * Step 2) Do a solr search for each "item" id in the searchableItems results
		 */
		List<SolrSearchResult> solrResults = getSolrSearchResults(searchString, facets);
		
		/*
		 * Step 3) Check result count and get the highest result object to show
		 */
		if(solrResults.size() == 0) {
			// Need to show an empty search result at the meta search page
			renderArgs.put("searchResultStatus", false);
			render();
		} else {
			SolrSearchResult maxResult = solrResults.get(0);
			
			int totalHits = 0;
			for(SolrSearchResult solrResult : solrResults) {
				if(solrResult.getCount() > 0) {
					totalHits++;
				}
				
				if(solrResult.getCount() > maxResult.getCount()) {
					maxResult = solrResult;
				}
			}
			
			if(maxResult.getCount() == 0) {
				// Need to show an empty search result at the meta search page	
				renderArgs.put("searchResultStatus", false);
				render();
			}
			
			/*
			 * Step 4) Show the results page
			 */
			// Need to show a result page based on the largest result found.
			// It needs to have a special left side "facet" bar that shows 
			// all "searchable item" results in facet form.
			// The "results" section needs to be a solr response w/ the full
			// result of the biggest return.  It will be either a "Detail" solr result
			// or a "Meta" solr result
			renderArgs.put("searchID", maxResult.getSearchItem().getId());
			renderArgs.put("searchType", maxResult.getSearchItem().getType().toLowerCase());
			renderArgs.put("searchResultCount", totalHits);
			renderArgs.put("searchResultStatus", true);
			if(maxResult.getSearchItem().getType().toLowerCase().equals("meta")) {
				// This is a meta search
				String toolTipTxt = "";
				
				if(totalHits > 1) {
					toolTipTxt = "The results shown for this Global Search are Meta results for the entire system.\\nThe search term requested \\\"" + searchString + 
										"\\\" was found in " + totalHits + " seperate searches.  This specific search result had the highest result count.";
					
					// We had more than 1 search result that hit this search term.  We need to add a fake facets on the left side of the page
					/*
					 	<div id="additional_search_results"></div>
					 	
					 	TURNS INTO:
						
						<div  id="additional_search_results" style="padding-bottom: 10px;">
							<div class="accordion-heading">
								<p class="accordion-toggle search_nav_title" style="cursor: auto !important;">
									<i class="icon-tasks" style="margin: 2px 0px 0px 0px;"></i> Additional Searches
								</p>
							</div>
							
							FOREACH DB IN RESULTS:
							
								<div class="accordion" id="accordion_column_texts">
									<div class="accordion-group search_nav_group">
										<div class="accordion-heading">
											<a id="table_toggle" class="accordion-toggle search_nav_title collapsed" data-toggle="collapse" data-parent="#accordion_column_texts" href="#collapse_column_texts">
												<!-- <i id="table_icon" class="icon-pause" style="margin: 2px 2px 0px 0px;"></i> -->DB 1 &nbsp;&nbsp;
												<i id="table_icon" class="icon-chevron-right" style="margin: 3px 10px 0px 0px;"></i>
												<span id="column_count_total" class="badge facet_count_total">20</span>
											</a>
										</div>
										<div id="collapse_column_texts" class="accordion-body collapse" style="height: 0px;">
											<div class="accordion=inner">
												<div id="column_texts">
													<table class="search_nav_facet_table">
														<tbody>
															<tr class="search_nav_facet_tr">
																<td class="search_nav_facet_td"><a href="#" class="tagcloud_item">TABLE 1</a></td>
																<td class="search_nav_facet_value_td"><span class="badge child_facet_count_total">7</span></td>
															</tr>
															<tr class="search_nav_facet_tr">
																<td class="search_nav_facet_td"><a href="#" class="tagcloud_item">TABLE 2</a></td>
																<td class="search_nav_facet_value_td"><span class="badge child_facet_count_total">7</span></td>
															</tr>
															</tbody>
														</table>
													</div>
											</div>
										</div>
									</div>
								</div>
						</div>
					 */
				} else {
					toolTipTxt = "The results shown for this Global Search are Meta results for the entire system.\\nThe search term requested \\\"" + searchString +
										"\\\"  was only found in this Meta search.";
				}
				renderArgs.put("searchToolTip", toolTipTxt);
			} else {
				// This is a detail search
				String toolTipTxt = "";
				
				if(totalHits > 1) {
					toolTipTxt = "The results shown for this Global Search are Detail results for the table \\\"" + maxResult.getSearchItem().getId() + 
						  "\\\".\\nThe search term requested \\\"" + searchString + "\\\" was found in " + totalHits + " seperate searches.  " +
						  "This specific search result had the highest result count.";
				} else {
					toolTipTxt = "The results shown for this Global Search are Detail results for the table " + maxResult.getSearchItem().getId() + 
						  ".\\nThe search term requested \\\"" + searchString + "\\\" was only found in this Detail search.";
				}
				renderArgs.put("searchToolTip", toolTipTxt);
			}
		}
		
		
		//renderArgs.put("solrURL", solrURL);
		
		//EntityUser user = Utility.getCurrentUser(session);
		//render(user);
		render();
	}
	
	private static List<SolrSearchResult> getSolrSearchResults(String query, Map<String, List<SearchItem>> searchItems) {
		EntityUser user = Utility.getCurrentUser(session);
		
		List<SolrSearchResult> results = new ArrayList<SolrSearchResult>();
		
		// wt=json&rows=0
		Map<String, String[]> params = Request.current().params.all();
		params.put("q", new String[] {query});
		params.put("wt", new String[] {"json"});
		params.put("rows", new String[] {"0"});
		params.remove("searchString");
		
		/**
		 * Threading doesnt work with Play atm due to a ThreadLocal issue.  We cannot
		 * thread calls from a specific session into the framework.
		 */
		/*
		// create a thread for each URI
		List<GetThread> threads = new Vector<GetThread>();
		for (List<SearchItem> searchItemList : searchItems.values()) {
		    for(SearchItem searchItem : searchItemList) {
			    threads.add(new GetThread(params, searchItem, user, Request.current()));
		    }
		}
		
		for(GetThread thread : threads) {
			thread.start();
		}		
		
		for(GetThread thread : threads) {
			try {
				thread.join();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		for(GetThread thread : threads) {
			SolrSearchResult searchResult = thread.getSearchResult();
			
			results.add(searchResult);
		}
		*/
		
		/**
		 * Until the ThreadLocal issue is fixed in Play we must serially call the Search 
		 * controller so it can get our search results for us.
		 */
		for (List<SearchItem> searchItemList : searchItems.values()) {
		    for(SearchItem searchItem : searchItemList) {
		    	String result = Search.secureSearchTable(searchItem.getId(), params, user, request);
		    	SolrSearchResult searchResult = new SolrSearchResult(result, searchItem);
		    	results.add(searchResult);
		    }
		}
		
		return results;
	}
	
	/**
	 * Class used to test Play's ThreadLocal code
	 */
	public static void justinsMostExcellentHackOfPlay(String userID, String table, Map<String, String[]> params, SolrSearchResult searchResult) {
		NoSqlEntityManagerFactory nsqlEMF =  NoSql.getEntityManagerFactory();
		NoSqlEntityManager manager = nsqlEMF.createEntityManager();
		
		// Just do a pure java call into the Search controller
		EntityUser user = manager.find(EntityUser.class, userID);
		
    		String result = Search.secureSearchTable(table, params, user, request);
    		searchResult.setResult(result);
	}
	
	/**
	 * Private classes for internal use only
	 * *****************************************************
	 */

	/**
	 * Snagged from http://hc.apache.org/httpcomponents-client-ga/tutorial/html/connmgmt.html#d5e639
	 */
	@SuppressWarnings("unused")
	static private class GetThread extends Thread {
		private Map<String, String[]> params;
		private SearchItem searchItem;
		private String userID;
		private Request request;
		
	    private SolrSearchResult searchResult;
	    
	    public GetThread(Map<String, String[]> params, SearchItem searchItem, EntityUser user, Request request) {
	    		this.params = params;
	    		this.searchItem = searchItem;
	    		this.userID = user.getId();
	    		this.request = request;
	    }
	    
	    @Override
	    public void run() {
	    		NoSqlEntityManagerFactory nsqlEMF =  NoSql.getEntityManagerFactory();
	    		NoSqlEntityManager manager = nsqlEMF.createEntityManager();
	    		
	    		// Just do a pure java call into the Search controller
	    		EntityUser user = manager.find(EntityUser.class, userID);
	    		
	        	String result = Search.secureSearchTable(searchItem.getId(), params, user, request);
	        	searchResult = new SolrSearchResult(result, this.searchItem);
	    }
	    
		public SolrSearchResult getSearchResult() {
			return searchResult;
		}	   
	}
}


















