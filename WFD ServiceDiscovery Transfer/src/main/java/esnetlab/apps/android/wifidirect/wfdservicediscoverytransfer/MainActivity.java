package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.location.Address;
import android.location.Criteria;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesClient;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.location.LocationClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;


public class MainActivity extends Activity implements LocationListener,
        GooglePlayServicesClient.ConnectionCallbacks,
        GooglePlayServicesClient.OnConnectionFailedListener {

    public static final String TAG = "wfdservicediscoverytransfer";
    public static final String INSTANCE_NAME = "_wfdsdt";
    public static final String SERVICE_TYPE = "_presence._tcp";
    public static final int PRUNE_REMOTE_HAZARDS_PERIOD = 1000;
    public static final int DISCOVER_SERVICE_PERIOD = 5000;
    public static final Hazards hazards = new Hazards();
    public static final List<Marker> mLocalMarkers = new ArrayList<Marker>();
    public static final List<Marker> mRemoteMarkers = new ArrayList<Marker>();
    public static final String HAZARD_ITEM = "HAZARD_ITEM";
    static final int settingsRequestCode = 0xf1;
    private static final String UNIQUE_ID = "MY_UNIQUE_ID";
    public static int uniqueID;
    // Handle to SharedPreferences for this app
    public static SharedPreferences mPrefs;
    // Handle to a SharedPreferences editor
    public static SharedPreferences.Editor mEditor;
    public static boolean isForwardingEnabled = false;
    public static boolean isEnforceHigherSeqNoEnabled;
    public static boolean isUpdateFromOriginalSourceEnabled;
    final HashMap<String, Map<String, String>> buddies = new HashMap<String, Map<String, String>>();
    private final IntentFilter intentFilter = new IntentFilter();
    /*
     * Note if updates have been turned on. Starts out as "false"; is set to
     * "true" in the method handleRequestSuccess of LocationUpdateReceiver.
     */
    boolean mUpdatesRequested = false;
    SharedPreferences sharedPref;
    private Timer pruneHazardsTimer, discoverServicesTimer;
    private PruneHazardsTask pruneHazardsTask;
    private DiscoverHazardServicesTask discoverHazardServicesTask;
    private WifiP2pManager.Channel mChannel;
    private WifiP2pManager mManager;
    private WifiP2pDnsSdServiceRequest serviceRequest;
    private TextView txtView;
    //WifiP2pManager.Channel mChannel;
    private WiFiDirectBroadcastReceiver receiver;
    // A request to connect to Location Services
    private LocationRequest mLocationRequest;
    // Stores the current instantiation of the location client in this object
    private LocationClient mLocationClient;
    private Location mLastLocation;
    private GoogleMap mMap;
    private boolean hazardDetected;

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        PreferenceManager.setDefaultValues(this, R.xml.pref_general, false);
        sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        setUserSettings();

        //  Indicates a change in the Wi-Fi P2P status.
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        // Indicates a change in the list of available peers.
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        // Indicates the state of Wi-Fi P2P connectivity has changed.
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        // Indicates this device's details have changed.
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        mManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        mChannel = mManager.initialize(this, getMainLooper(), null);

        receiver = new WiFiDirectBroadcastReceiver(mManager, mChannel, this);

        Button btnAddService = (Button) findViewById(R.id.buttonAddService);
        btnAddService.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hazardDetected = true;
                //clearLocalHazardServices();
                startHazardRegistration();
                //startPeriodicUpdates();
            }
        });

        Button btnDiscover = (Button) findViewById(R.id.buttonDiscover);
        btnDiscover.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //hazardDetected = false;
                //clearHazardServiceRequests();
                discoverHazardService();
                //stopPeriodicUpdates();
                isForwardingEnabled = !isForwardingEnabled;

            }
        });

        txtView = (TextView) findViewById(R.id.txtStatus);
        txtView.setMovementMethod(new ScrollingMovementMethod());

        // Create a new global location parameters object
        mLocationRequest = LocationRequest.create();

		/*
         * Set the update interval
		 */
        mLocationRequest
                .setInterval(LocationUtils.UPDATE_INTERVAL_IN_MILLISECONDS);

        // Use high accuracy
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        // Set the interval ceiling to one minute
        mLocationRequest
                .setFastestInterval(LocationUtils.FAST_INTERVAL_CEILING_IN_MILLISECONDS);

        // Note that location updates are off until the user turns them on
        mUpdatesRequested = false;

        // Open Shared Preferences
        mPrefs = getSharedPreferences(LocationUtils.SHARED_PREFERENCES,
                Context.MODE_PRIVATE);

        // Get an editor
        mEditor = mPrefs.edit();

		/*
         * Create a new location client, using the enclosing class to handle
		 * callbacks.
		 */
        mLocationClient = new LocationClient(this, this, this);

        hazardDetected = false;

        if (mPrefs.contains(UNIQUE_ID)) {
            uniqueID = mPrefs.getInt(UNIQUE_ID, 0);
            if (uniqueID == 0) {
                uniqueID = (int) (Math.random() * 10000);
            }
        } else {
            uniqueID = (int) (Math.random() * 10000);
        }

        setTitle(getTitle() + ":" + uniqueID);
        mEditor.putInt(UNIQUE_ID, uniqueID);

        mEditor.commit();
        setUpMapIfNeeded();
    }

    private void setUserSettings() {
        isForwardingEnabled = sharedPref.getBoolean("allow_forwarding_checkbox", false);
        isEnforceHigherSeqNoEnabled = sharedPref.getBoolean("enforce_higher_seq_checkbox", false);
        isUpdateFromOriginalSourceEnabled = sharedPref.getBoolean("update_from_original_source_checkbox", false);
    }

    private void setUpMapIfNeeded() {
        // Do a null check to confirm that we have not already instantiated the map.
        if (mMap == null) {
            mMap = ((MapFragment) getFragmentManager().findFragmentById(R.id.map))
                    .getMap();
            // Check if we were successful in obtaining the map.
            if (mMap != null) {
                // The Map is verified. It is now safe to manipulate the map.
                mMap.setMyLocationEnabled(true);
                //if(mLocationClient != null) {
                Criteria criteria = new Criteria();
                LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                String provider = locationManager.getBestProvider(criteria, false);
                Location location = locationManager.getLastKnownLocation(provider);
                //Location location = mLocationClient.getLastLocation();
                if (location != null) {
                    LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());
                    mMap.moveCamera(CameraUpdateFactory.newLatLng(latLng));
                }
                //}

                mMap.setOnMapLongClickListener(new GoogleMap.OnMapLongClickListener() {
                    @Override
                    public void onMapLongClick(LatLng latLng) {
                        if (latLng != null) {
                            HazardItem hazardItem = hazards.addLocalHazard(
                                    getApplicationContext(),
                                    uniqueID,
                                    LocationUtils.getLocationFromLatLng(latLng));
                            startHazardRegistration(hazardItem);
                            addLocalMapMarker(latLng, hazardItem);
                        }
                    }
                });

                mMap.setOnMarkerClickListener(new GoogleMap.OnMarkerClickListener() {
                    @Override
                    public boolean onMarkerClick(Marker marker) {
                        if (mLocalMarkers.contains(marker)) {
                            HazardItem hazardItem = hazards.getLocalHazardItem(marker);
                            hazardItem.setStillValid(false);
                            hazardItem.setMarker(null);
                            marker.setVisible(false);
                            mLocalMarkers.remove(marker);
                            marker.remove();

                            reRegisterHazards();
                        }
                        return false;
                    }
                });
            }
        }
    }

    private void addLocalMapMarker(Location location, HazardItem hazardItem) {
        LatLng latLng = LocationUtils.getLatLngFromLocation(location);
        if (latLng != null) {
            addLocalMapMarker(latLng, hazardItem);
        }
    }

    private void addLocalMapMarker(LatLng latLng, HazardItem hazardItem) {
        if (mMap != null) {
            Marker marker = mMap.addMarker(new MarkerOptions()
                    .title(hazardItem.getLocalMapMarkerTitle())
                    .snippet(hazardItem.getLocalMapMarkerSnippet())
                    .position(latLng)
                    .visible(true));
            marker.showInfoWindow();

            hazardItem.setMarker(marker);
            mLocalMarkers.add(marker);
        }
    }

    private void addRemoteMapMarker(Location location, HazardItem hazardItem) {
        LatLng latLng = LocationUtils.getLatLngFromLocation(location);
        if (latLng != null) {
            addRemoteMapMarker(latLng, hazardItem);
        }
    }

    private void addRemoteMapMarker(LatLng latLng, HazardItem hazardItem) {
        if (mMap != null) {
            Marker marker = mMap.addMarker(new MarkerOptions()
                    .title(hazardItem.getRemoteMapMarkerTitle())
                    .snippet(hazardItem.getRemoteMapMarkerSnippet())
                    .position(latLng)
                    .visible(true)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
            //marker.showInfoWindow();

            hazardItem.setMarker(marker);
            mRemoteMarkers.add(marker);
        }
    }

    /**
     * register the BroadcastReceiver with the intent values to be matched
     */
    @Override
    public void onResume() {
        super.onResume();
        receiver = new WiFiDirectBroadcastReceiver(mManager, mChannel, this);
        registerReceiver(receiver, intentFilter);

        pruneHazardsTimer = new Timer("pruneHazardsTimer");
        pruneHazardsTask = new PruneHazardsTask();
        pruneHazardsTask.activity = this;

        pruneHazardsTimer.schedule(pruneHazardsTask, 0, PRUNE_REMOTE_HAZARDS_PERIOD);

        discoverServicesTimer = new Timer("discoverServicesTimer");
        discoverHazardServicesTask = new DiscoverHazardServicesTask();
        discoverHazardServicesTask.activity = this;

        discoverServicesTimer.schedule(discoverHazardServicesTask, 0, DISCOVER_SERVICE_PERIOD);

        reRegisterHazards();

        // If the app already has a setting for getting location updates, get it
        if (mPrefs.contains(LocationUtils.KEY_UPDATES_REQUESTED)) {
            mUpdatesRequested = mPrefs.getBoolean(
                    LocationUtils.KEY_UPDATES_REQUESTED, false);

            // Otherwise, turn off location updates until requested
        } else {
            mEditor.putBoolean(LocationUtils.KEY_UPDATES_REQUESTED, false);
            mEditor.commit();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterReceiver(receiver);

        pruneHazardsTimer.cancel();
        pruneHazardsTimer.purge();
        pruneHazardsTimer = null;
        pruneHazardsTask.cancel();
        pruneHazardsTask = null;

        discoverServicesTimer.cancel();
        discoverServicesTimer.purge();
        discoverServicesTimer = null;
        discoverHazardServicesTask.cancel();
        discoverHazardServicesTask = null;

        clearLocalHazardServices();
        clearHazardServiceRequests();

        // Save the current setting for updates
        mEditor.putBoolean(LocationUtils.KEY_UPDATES_REQUESTED,
                mUpdatesRequested);
        mEditor.commit();

        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            showSettings();
        } else if (id == R.id.action_clear) {
            clearStatus();
        } else if (id == R.id.action_toggle_wifi) {
            toggleWifiState();
        } else if (id == R.id.action_start_location_updates) {
            startPeriodicUpdates();
        } else if (id == R.id.action_stop_location_updates) {
            stopPeriodicUpdates();
        } else if (id == R.id.action_clear_local_hazards) {
            clearLocalHazards();
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivityForResult(intent, settingsRequestCode);
    }

    private void clearLocalHazards() {
        for (HazardItem hazardItem : Hazards.localHazardList) {
            hazardItem.setStillValid(false);
            hazardItem.getMarker().setVisible(false);
            hazardItem.getMarker().remove();

            mLocalMarkers.clear();
            reRegisterHazards();
        }
    }

    private void toggleWifiState() {
        WifiManager mgr = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        mgr.setWifiEnabled(!(mgr.getWifiState() == WifiManager.WIFI_STATE_ENABLED));
    }

    private void clearStatus() {
        if (txtView != null)
            txtView.setText("");
    }

    public void clearLocalHazardServices() {
        if (mManager != null && mChannel != null) {
            mManager.clearLocalServices(mChannel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.d(TAG, "clearLocalHazardServices -> success");
                    appendStatus("clearLocalHazardServices -> success");
                }

                @Override
                public void onFailure(int reason) {
                    Log.d(TAG, "clearLocalHazardServices -> failure");
                    appendStatus("clearLocalHazardServices -> failure");
                }
            });
        }
    }

    public void clearHazardServiceRequests() {
        if (mManager != null && mChannel != null) {
            mManager.clearServiceRequests(mChannel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.d(TAG, "clearHazardServiceRequests -> success");
                    appendStatus("clearHazardServiceRequests -> success");
                }

                @Override
                public void onFailure(int reason) {
                    Log.d(TAG, "clearHazardServiceRequests -> failure");
                    appendStatus("clearHazardServiceRequests -> failure");
                }
            });
        }
    }

    public void reRegisterHazards() {
        clearLocalHazardServices();
        for (HazardItem hazardItem : Hazards.localHazardList) {
            startHazardRegistration(hazardItem);
        }
//TODO activate the following code
        if (isForwardingEnabled) {
            for (ArrayList<HazardItem> hazardItems : Hazards.remoteHazards.values()) {
                for (HazardItem hazardItem : hazardItems) {
                    startHazardRegistration(hazardItem);
                }
            }
        }
    }

    public void startHazardRegistration() {
        Location loc = getHazardLocation();
        HazardItem hz;
        if (loc != null) {
            hz = hazards.addLocalHazard(this, uniqueID, loc);
            addLocalMapMarker(loc, hz);
        } else {
            return;
        }
        startHazardRegistration(hz);
    }

    public void startHazardRegistration(HazardItem hazardItem) {
        //  Create a string map containing information about your service.
        Map<String, String> record = new HashMap<String, String>();

        record.put(HAZARD_ITEM, hazardItem.toString());

        // Service information.  Pass it an instance name, service type
        // _protocol._transportlayer , and the map containing
        // information other devices will want once they connect to this one.
        WifiP2pDnsSdServiceInfo serviceInfo =
                WifiP2pDnsSdServiceInfo.newInstance(INSTANCE_NAME, SERVICE_TYPE, record);

        // Add the local service, sending the service info, network mChannel,
        // and listener that will be used to indicate success or failure of
        // the request.
        mManager.addLocalService(mChannel, serviceInfo, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                // Command successful! Code isn't necessarily needed here,
                // Unless you want to update the UI or add logging statements.
                Log.d(TAG, "startHazardRegistration -> success");
                appendStatus("startHazardRegistration -> success");
            }

            @Override
            public void onFailure(int reason) {
                // Command failed.  Check for P2P_UNSUPPORTED, ERROR, or BUSY
                Log.d(TAG, "startHazardRegistration -> failure");
                appendStatus("startHazardRegistration -> failure");
            }
        });
    }

    private String getHazardLocationString() {
        if (hazardDetected)
            if (mLastLocation != null)
                return LocationUtils.getLatLng(this, mLastLocation);
            else
                return LocationUtils.getLatLng(this, mLocationClient.getLastLocation());
        else
            return "N/A";
    }

    private Location getHazardLocation() {
        if (hazardDetected)
            if (mLastLocation != null)
                return mLastLocation;
            else
                return mLocationClient.getLastLocation();
        else
            return null;
    }

    public void discoverHazardService() {
        WifiP2pManager.DnsSdTxtRecordListener txtListener = new WifiP2pManager.DnsSdTxtRecordListener() {
            @Override
        /* Callback includes:
         * fullDomain: full domain name: e.g "printer._ipp._tcp.local."
         * record: TXT record dta as a map of key/value pairs.
         * device: The device running the advertised service.
         */

            public void onDnsSdTxtRecordAvailable(String fullDomain, Map record,
                                                  WifiP2pDevice device) {
                Log.d(TAG, "Hazard DnsSdTxtRecord available -" + record.toString());
                //TODO Replace the next line with appropriate hazard temporary storage logic.
                buddies.put(device.deviceAddress, record);
                appendStatus("Hazard Record available -> " + record.get(HAZARD_ITEM));
            }

        };

        WifiP2pManager.DnsSdServiceResponseListener servListener = new WifiP2pManager.DnsSdServiceResponseListener() {
            @Override
            public void onDnsSdServiceAvailable(String instanceName, String registrationType,
                                                WifiP2pDevice resourceType) {

                if (instanceName.equals(INSTANCE_NAME)) {
                    //TODO Add some logic to correctly store and forward the hazard. If the hazard is no longer valid it should be noted to others as not valid.

                    if (buddies.containsKey(resourceType.deviceAddress)) {
                        HazardItem hazardItem = updateRemoteHazardsAndMap(buddies.get(resourceType.deviceAddress), resourceType.deviceAddress);
                        if (hazardItem != null) {
                            //Forward the hazard
                            reRegisterHazards();
                            //Remove the record if it is no longer valid
                        }
                    }

                    Log.d(TAG, "onDnsServiceAvailable -> success -> " + instanceName);
                    appendStatus("onDnsServiceAvailable -> success -> " + resourceType.deviceName);
                } else {
                    Log.d(TAG, "onDnsServiceAvailable -> unknown  -> " + instanceName);
                    appendStatus("onDnsServiceAvailable -> unknown -> " + instanceName);
                }
            }
        };

        mManager.setDnsSdResponseListeners(mChannel, servListener, txtListener);

        serviceRequest = WifiP2pDnsSdServiceRequest.newInstance();
        mManager.addServiceRequest(mChannel,
                serviceRequest,
                new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        // Success!
                        Log.d(TAG, "addServiceRequest -> success");
                        appendStatus("addServiceRequest -> success");
                    }

                    @Override
                    public void onFailure(int code) {
                        // Command failed.  Check for P2P_UNSUPPORTED, ERROR, or BUSY
                        Log.d(TAG, "addServiceRequest -> failure");
                        appendStatus("addServiceRequest -> failure");
                    }
                }
        );

        mManager.discoverServices(mChannel, new WifiP2pManager.ActionListener() {

            @Override
            public void onSuccess() {
                // Success!
                Log.d(TAG, "discoverServices -> success");
                appendStatus("discoverServices -> success");
            }

            @Override
            public void onFailure(int code) {
                // Command failed.  Check for P2P_UNSUPPORTED, ERROR, or BUSY
                if (code == WifiP2pManager.P2P_UNSUPPORTED) {
                    Log.d(TAG, "P2P isn't supported on this device.");
                    Log.d(TAG, "discoverServices -> failure");
                    appendStatus("discoverServices -> failure");
                } else {
                }
            }
        });
    }

    private HazardItem updateRemoteHazardsAndMap(Map<String, String> record, String srcDevAddress) {
        if (record.containsKey(HAZARD_ITEM)) {
            String hRec = record.get(HAZARD_ITEM);
            HazardItem hazardItem = hazards.addRemoteHazard(this, hRec, srcDevAddress);
            //if hazardItem is null this means that the record originated from me and forwarded by someone else.
            if (hazardItem != null) {
                //Check if the hazard is valid. Valid means the hazard is still in effect. Not valid means that the hazard is to be removed.
                if (hazardItem.isStillValid()) {
                    if (mMap != null) {
                        float zoomLevel = mMap.getMaxZoomLevel() * 0.75f;
                        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                                LocationUtils.getLatLngFromLocation(hazardItem.gethLocation()),
                                zoomLevel));

                        removeRemoteMapMarker(hazardItem);
                        addRemoteMapMarker(hazardItem.gethLocation(), hazardItem);
                    }

                    Log.d(TAG, "Hazard detected at location: " + hazardItem.gethLocation());
                    appendStatus("Hazard detected at location: " + hazardItem.gethLocation());
                } else {
                    removeRemoteMapMarker(hazardItem);
                }
            }
            return hazardItem;
        }
        return null;
    }

    private void removeRemoteMapMarker(HazardItem hazardItem) {
        if (mRemoteMarkers.contains(hazardItem.getMarker())) {
            Marker marker = hazardItem.getMarker();
            marker.setVisible(false);
            marker.remove();
            mRemoteMarkers.remove(marker);
        }
    }


    public boolean pruneInactiveRemoteHazardItems() {
        hazards.decreaseRemoteHazardItemsTTLs();
        ArrayList<HazardItem> iHazardItems = hazards.getInactiveRemoteHazardItems();

        if (iHazardItems.size() > 0) {
            Log.d(TAG, iHazardItems.size() + " Inactive remote hazards found");
            appendStatus(iHazardItems.size() + " Inactive remote hazards found");
            for (HazardItem iHazardItem : iHazardItems) {
                removeRemoteMapMarker(iHazardItem);
                Log.d(TAG, "Removing marker for inactive remote hazard ->" + iHazardItem.toString());
                appendStatus("Removing marker for inactive remote hazard ->" + iHazardItem.toString());
            }

            return hazards.removeInactiveRemoteHazardItems();
        }
        return false;
    }

    public boolean pruneInactiveLocalHazardItems() {
        hazards.decreaseLocalHazardItemsTTLs();
        return hazards.removeInactiveLocalHazardItems();
    }

    public void appendStatus(String status) {
        if (txtView != null) {
            String str = txtView.getText().toString();
            str = str + "\n" + status;
            txtView.setText(str);
        }
    }

    public void appendStatus(int resourceStringID) {
        try {
            String resStr = getResources().getString(resourceStringID);
            appendStatus(resStr);
        } catch (Resources.NotFoundException ex) {
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        // If the client is connected
        if (mLocationClient.isConnected()) {
            stopPeriodicUpdates();
        }

        // After disconnect() is called, the client is considered "dead".
        mLocationClient.disconnect();

        super.onStop();
    }

    @Override
    protected void onStart() {
        super.onStart();

        		/*
         * Connect the client. Don't re-start any requests here; instead, wait
		 * for onResume()
		 */
        mLocationClient.connect();
        //setUpMapIfNeeded();
    }

    /*
* Handle results returned to this Activity by other Activities started with
* startActivityForResult(). In particular, the method onConnectionFailed()
* in LocationUpdateRemover and LocationUpdateRequester may call
* startResolutionForResult() to start an Activity that handles Google Play
* services problems. The result of this call returns here, to
* onActivityResult.
*/
    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent intent) {

        // Choose what to do based on the request code
        switch (requestCode) {

            // If the request code matches the code sent in onConnectionFailed
            case LocationUtils.CONNECTION_FAILURE_RESOLUTION_REQUEST:

                switch (resultCode) {
                    // If Google Play services resolved the problem
                    case Activity.RESULT_OK:

                        // Log the result
                        Log.d(LocationUtils.APPTAG, getString(R.string.resolved));

                        // Display the result
                        appendStatus(R.string.connected);
                        appendStatus(R.string.resolved);
                        break;

                    // If any other result was returned by Google Play services
                    default:
                        // Log the result
                        Log.d(LocationUtils.APPTAG, getString(R.string.no_resolution));

                        // Display the result
                        appendStatus(R.string.disconnected);
                        appendStatus(R.string.no_resolution);

                        break;
                }
            case settingsRequestCode:
                setUserSettings();
                break;

            // If any other request code was received
            default:
                // Report that this Activity received an unknown requestCode
                Log.d(LocationUtils.APPTAG,
                        getString(R.string.unknown_activity_request_code,
                                requestCode)
                );

                break;
        }
    }

    /**
     * Verify that Google Play services is available before making a request.
     *
     * @return true if Google Play services is available, otherwise false
     */
    private boolean servicesConnected() {

        // Check that Google Play services is available
        int resultCode = GooglePlayServicesUtil
                .isGooglePlayServicesAvailable(this);

        // If Google Play services is available
        if (ConnectionResult.SUCCESS == resultCode) {
            // In debug mode, log the status
            Log.d(LocationUtils.APPTAG,
                    getString(R.string.play_services_available));

            // Continue
            return true;
            // Google Play services was not available for some reason
        } else {
            // Display an error dialog
            Dialog dialog = GooglePlayServicesUtil.getErrorDialog(resultCode,
                    this, 0);
            if (dialog != null) {
                ErrorDialogFragment errorFragment = new ErrorDialogFragment();
                errorFragment.setDialog(dialog);
                errorFragment.show(getFragmentManager(),
                        LocationUtils.APPTAG);
            }
            return false;
        }
    }

    /**
     * Invoked by the "Get Location" button.
     * <p/>
     * Calls getLastLocation() to get the current location
     *
     * @param v The view object associated with this method, in this case a
     *          Button.
     */
    public void getLocation(View v) {

        // If Google Play Services is available
        if (servicesConnected()) {

            // Get the current location
            Location currentLocation = mLocationClient.getLastLocation();

            // Display the current location in the UI
            appendStatus(LocationUtils.getLatLng(this, currentLocation));
        }
    }

    /**
     * Invoked by the "Get Address" button. Get the address of the current
     * location, using reverse geocoding. This only works if a geocoding service
     * is available.
     *
     * @param v The view object associated with this method, in this case a
     *          Button.
     */
    // For Eclipse with ADT, suppress warnings about Geocoder.isPresent()
    @SuppressLint("NewApi")
    public void getAddress(View v) {

        // In Gingerbread and later, use Geocoder.isPresent() to see if a
        // geocoder is available.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD
                && !Geocoder.isPresent()) {
            // No geocoder is present. Issue an error message
            Toast.makeText(this, R.string.no_geocoder_available,
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (servicesConnected()) {

            // Get the current location
            Location currentLocation = mLocationClient.getLastLocation();

            // Turn the indefinite activity indicator on
            //mActivityIndicator.setVisibility(View.VISIBLE);

            // Start the background task
            (new MainActivity.GetAddressTask(this)).execute(currentLocation);
        }
    }

    /**
     * Invoked by the "Start Updates" button Sends a request to start location
     * updates
     *
     * @param v The view object associated with this method, in this case a
     *          Button.
     */
    public void startUpdates(View v) {
        mUpdatesRequested = true;

        if (servicesConnected()) {
            startPeriodicUpdates();
        }
    }

    /**
     * Invoked by the "Stop Updates" button Sends a request to remove location
     * updates request them.
     *
     * @param v The view object associated with this method, in this case a
     *          Button.
     */
    public void stopUpdates(View v) {
        mUpdatesRequested = false;

        if (servicesConnected()) {
            stopPeriodicUpdates();
        }
    }

    /*
     * Called by Location Services when the request to connect the client
     * finishes successfully. At this point, you can request the current
     * location or start periodic updates
     */
    @Override
    public void onConnected(Bundle bundle) {
        appendStatus(R.string.connected);

        if (mUpdatesRequested) {
            startPeriodicUpdates();
        }
    }

    /*
     * Called by Location Services if the connection to the location client
     * drops because of an error.
     */
    @Override
    public void onDisconnected() {
        appendStatus(R.string.disconnected);
    }

    /*
     * Called by Location Services if the attempt to Location Services fails.
     */
    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {

		/*
         * Google Play services can resolve some errors it detects. If the error
		 * has a resolution, try sending an Intent to start a Google Play
		 * services activity that can resolve error.
		 */
        if (connectionResult.hasResolution()) {
            try {

                // Start an Activity that tries to resolve the error
                connectionResult.startResolutionForResult(this,
                        LocationUtils.CONNECTION_FAILURE_RESOLUTION_REQUEST);

				/*
                 * Thrown if Google Play services canceled the original
				 * PendingIntent
				 */

            } catch (IntentSender.SendIntentException e) {

                // Log the error
                e.printStackTrace();
            }
        } else {

            // If no resolution is available, display a dialog to the user with
            // the error.
            showErrorDialog(connectionResult.getErrorCode());
        }
    }

    /**
     * Report location updates to the UI.
     *
     * @param location The updated location.
     */
    @Override
    public void onLocationChanged(Location location) {

        // Report to the UI that the location was updated
        appendStatus(R.string.location_updated);

        // In the UI, set the latitude and longitude to the value received
        appendStatus(LocationUtils.getLatLng(this, location));

        mLastLocation = location;
    }

    /**
     * In response to a request to start updates, send a request to Location
     * Services
     */
    private void startPeriodicUpdates() {

        mLocationClient.requestLocationUpdates(mLocationRequest, this);
        appendStatus(R.string.location_requested);
    }

    /**
     * In response to a request to stop updates, send a request to Location
     * Services
     */
    private void stopPeriodicUpdates() {
        mLocationClient.removeLocationUpdates(this);
        appendStatus(R.string.location_updates_stopped);
    }

    /**
     * Show a dialog returned by Google Play services for the connection error
     * code
     *
     * @param errorCode An error code returned from onConnectionFailed
     */
    private void showErrorDialog(int errorCode) {

        // Get the error dialog from Google Play services
        Dialog errorDialog = GooglePlayServicesUtil.getErrorDialog(errorCode,
                this, LocationUtils.CONNECTION_FAILURE_RESOLUTION_REQUEST);

        // If Google Play services can provide an error dialog
        if (errorDialog != null) {

            // Create a new DialogFragment in which to show the error dialog
            ErrorDialogFragment errorFragment = new ErrorDialogFragment();

            // Set the dialog in the DialogFragment
            errorFragment.setDialog(errorDialog);

            // Show the error dialog in the DialogFragment
            errorFragment.show(getFragmentManager(),
                    LocationUtils.APPTAG);
        }
    }

    /**
     * Define a DialogFragment to display the error dialog generated in
     * showErrorDialog.
     */
    public static class ErrorDialogFragment extends DialogFragment {

        // Global field to contain the error dialog
        private Dialog mDialog;

        /**
         * Default constructor. Sets the dialog field to null
         */
        public ErrorDialogFragment() {
            super();
            mDialog = null;
        }

        /**
         * Set the dialog to display
         *
         * @param dialog An error dialog
         */
        public void setDialog(Dialog dialog) {
            mDialog = dialog;
        }

        /*
         * This method must return a Dialog to the DialogFragment.
         */
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return mDialog;
        }
    }

    /**
     * An AsyncTask that calls getFromLocation() in the background. The class
     * uses the following generic types: Location - A
     * {@link android.location.Location} object containing the current location,
     * passed as the input parameter to doInBackground() Void - indicates that
     * progress units are not used by this subclass String - An address passed
     * to onPostExecute()
     */
    protected class GetAddressTask extends AsyncTask<Location, Void, String> {

        // Store the context passed to the AsyncTask when the system
        // instantiates it.
        Context localContext;

        // Constructor called by the system to instantiate the task
        public GetAddressTask(Context context) {

            // Required by the semantics of AsyncTask
            super();

            // Set a Context for the background task
            localContext = context;
        }

        /**
         * Get a geocoding service instance, pass latitude and longitude to it,
         * format the returned address, and return the address to the UI thread.
         */
        @Override
        protected String doInBackground(Location... params) {
            /*
             * Get a new geocoding service instance, set for localized
			 * addresses. This example uses android.location.Geocoder, but other
			 * geocoders that conform to address standards can also be used.
			 */
            Geocoder geocoder = new Geocoder(localContext, Locale.getDefault());

            // Get the current location from the input parameter list
            Location location = params[0];

            // Create a list to contain the result address
            List<Address> addresses = null;

            // Try to get an address for the current location. Catch IO or
            // network problems.
            try {

				/*
                 * Call the synchronous getFromLocation() method with the
				 * latitude and longitude of the current location. Return at
				 * most 1 address.
				 */
                addresses = geocoder.getFromLocation(location.getLatitude(),
                        location.getLongitude(), 1);

                // Catch network or other I/O problems.
            } catch (IOException exception1) {

                // Log an error and return an error message
                Log.e(LocationUtils.APPTAG,
                        getString(R.string.IO_Exception_getFromLocation));

                // print the stack trace
                exception1.printStackTrace();

                // Return an error message
                return (getString(R.string.IO_Exception_getFromLocation));

                // Catch incorrect latitude or longitude values
            } catch (IllegalArgumentException exception2) {

                // Construct a message containing the invalid arguments
                String errorString = getString(
                        R.string.illegal_argument_exception,
                        location.getLatitude(), location.getLongitude());
                // Log the error and print the stack trace
                Log.e(LocationUtils.APPTAG, errorString);
                exception2.printStackTrace();

                //
                return errorString;
            }
            // If the reverse geocode returned an address
            if (addresses != null && addresses.size() > 0) {

                // Get the first address
                Address address = addresses.get(0);

                // Format the first line of address
                String addressText = getString(
                        R.string.address_output_string,

                        // If there's a street address, add it
                        address.getMaxAddressLineIndex() > 0 ? address
                                .getAddressLine(0) : "",

                        // Locality is usually a city
                        address.getLocality(),

                        // The country of the address
                        address.getCountryName()
                );

                // Return the text
                return addressText;

                // If there aren't any addresses, post a message
            } else {
                return getString(R.string.no_address_found);
            }
        }

        /**
         * A method that's called once doInBackground() completes. Set the text
         * of the UI element that displays the address. This method runs on the
         * UI thread.
         */
        @Override
        protected void onPostExecute(String address) {

            // Turn off the progress bar
            //mActivityIndicator.setVisibility(View.GONE);

            // Set the address in the UI
            appendStatus(address);
        }
    }
}

class PruneHazardsTask extends TimerTask {
    public MainActivity activity = null;

    public void run() {
        if (activity != null) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    boolean needReRegistration;
                    boolean cond1, cond2;

                    cond1 = activity.pruneInactiveRemoteHazardItems();
                    if (cond1) {
                        Log.d(activity.TAG, "One or more inactive remote hazards are removed");
                        activity.appendStatus("One or more inactive remote hazards are removed");
                    }

                    cond2 = activity.pruneInactiveLocalHazardItems();
                    if (cond2) {
                        Log.d(activity.TAG, "One or more inactive local hazards are removed");
                        activity.appendStatus("One or more inactive local hazards are removed");
                    }

                    needReRegistration = cond1 || cond2;
                    if (needReRegistration) {
                        activity.reRegisterHazards();
                        Log.d(MainActivity.TAG, "reRegisterHazards after pruning");
                        activity.appendStatus("reRegisterHazards after pruning");
                    }
                }
            });
        }
    }
}

class DiscoverHazardServicesTask extends TimerTask {
    public MainActivity activity = null;

    public void run() {
        if (activity != null) {
            activity.clearHazardServiceRequests();
            activity.discoverHazardService();
        }
    }
}
