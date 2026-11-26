package esnetlab.apps.android.wifidirect.wfdservicediscoverytransfer;

import android.content.Context;
import android.location.Location;

import com.google.android.gms.maps.model.Marker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by Ahmed on 5/15/2014.
 */
public class Hazards {
    public static final List<HazardItem> localHazardList = new ArrayList<HazardItem>();
    public static final Map<Integer, ArrayList<HazardItem>> remoteHazards = new HashMap<Integer, ArrayList<HazardItem>>();
    public static final Map<Integer, Integer> remoteBiggestSeq = new HashMap<Integer, Integer>();
    public static final String CUR_SEQ_NO = "CUR_SEQ_NO";
    public int myHazardSeqNo;

    public HazardItem addLocalHazard(Context context, int myUnigueID, Location hLocation) {
        int seqNo = 0;

        if ((MainActivity.mPrefs != null) && (MainActivity.mEditor != null)) {
            myHazardSeqNo = MainActivity.mPrefs.getInt(CUR_SEQ_NO, 0);
            seqNo = myHazardSeqNo;
            MainActivity.mEditor.putInt(CUR_SEQ_NO, ++myHazardSeqNo);
            MainActivity.mEditor.commit();
        }

        HazardItem hazardItem = new HazardItem(context, myUnigueID, seqNo, hLocation, null);
        //hazardItem.setMarker(marker);
        localHazardList.add(hazardItem);

        return hazardItem;
    }

    public HazardItem getLocalHazardItem(Marker marker) {
        for (HazardItem hazardItem : localHazardList) {
            if (hazardItem.getMarker() != null) {
                if (hazardItem.getMarker().equals(marker)) {
                    return hazardItem;
                }
            }
        }
        return null;
    }

    public HazardItem addRemoteHazard(Context context, String record, String srcDevAddress) {
        String[] recParts;
        recParts = record.split(",");
        int devId = Integer.valueOf(recParts[0]);
        int seqNo = Integer.valueOf(recParts[1]);
        String lat = recParts[2];
        String lng = recParts[3];
        boolean isValid = recParts[4].equals("1");
        Location loc = LocationUtils.getLocationFromLatLng(lat, lng);

        //check if the hazard is not originated by me (forwarded by someone else)
        if (devId == MainActivity.uniqueID)
            return null;

        ArrayList<HazardItem> hazardItems;

        if (remoteHazards.containsKey(devId)) {
            hazardItems = remoteHazards.get(devId);
        } else {
            hazardItems = new ArrayList<HazardItem>();
            remoteHazards.put(devId, hazardItems);
        }

        HazardItem hazardItem = searchHazardInList(devId, seqNo, hazardItems);
        if (hazardItem != null) {
            //Enforce the hazard validity to false if it was previously false. As, in this situation we
            //don't need to listen to broadcast from unupdated devices. Only the originator can set the
            //validity to false, so if true is received it is an outdated record.
            boolean isNotValid = (!isValid) || (!hazardItem.isStillValid());
            if (isNotValid) {
                hazardItem.setStillValid(false);
            } else {
                //Don't update unless the sender is the same one we received from him this message before.
                if (srcDevAddress.equals(hazardItem.getSrcDevAddress()) || (!MainActivity.isUpdateFromOriginalSourceEnabled)) {
                    hazardItem.updateLocation(loc);
                    hazardItem.resetTTL();
                }
            }
        } else {
            //check if the hazard is new, not an old one that is dangling.
            if (MainActivity.isEnforceHigherSeqNoEnabled) {
                if (remoteBiggestSeq.containsKey(devId))
                    if (seqNo <= remoteBiggestSeq.get(devId))
                        return null;
            }

            hazardItem = new HazardItem(context, devId, seqNo, loc, srcDevAddress);
            hazardItems.add(hazardItem);
            remoteBiggestSeq.put(devId, seqNo);
        }

        return hazardItem;
    }

    public HazardItem searchHazardInList(int devId, int seqNo, List<HazardItem> hazardItems) {
        for (HazardItem hazardItem : hazardItems) {
            if (hazardItem.getDeviceID() == devId)
                if (hazardItem.gethSeqNo() == seqNo)
                    return hazardItem;
        }
        return null;
    }

    public void decreaseLocalHazardItemsTTLs() {
        for (HazardItem hazardItem : localHazardList) {
            if (!hazardItem.isStillValid()) {
                hazardItem.decreaseTTL();
            }
        }
    }

    public ArrayList<HazardItem> getInactiveLocalHazardItems() {
        ArrayList<HazardItem> iHazardItems = new ArrayList<HazardItem>();
        for (HazardItem hazardItem : localHazardList) {
            if (hazardItem.getTtl() <= 0) {
                iHazardItems.add(hazardItem);
            }
        }
        return iHazardItems;
    }

    public boolean removeInactiveLocalHazardItems() {
        boolean hasInactiveHazard = false;
        ArrayList<HazardItem> iHazardItems = getInactiveLocalHazardItems();

        for (HazardItem iHazardItem : iHazardItems) {
            localHazardList.remove(iHazardItem);
            hasInactiveHazard = true;
        }
        return hasInactiveHazard;
    }


    public void decreaseRemoteHazardItemsTTLs() {
        for (ArrayList<HazardItem> hazardItems : remoteHazards.values()) {
            for (HazardItem hazardItem : hazardItems) {
                hazardItem.decreaseTTL();
            }
        }
    }

    public ArrayList<HazardItem> getInactiveRemoteHazardItems() {
        ArrayList<HazardItem> iHazardItems = new ArrayList<HazardItem>();
        for (ArrayList<HazardItem> hazardItems : remoteHazards.values()) {
            for (HazardItem hazardItem : hazardItems) {
                if (hazardItem.getTtl() <= 0) {
                    iHazardItems.add(hazardItem);
                }
            }
        }
        return iHazardItems;
    }

    public boolean removeInactiveRemoteHazardItems() {
        boolean hasInactiveHazard = false;
        ArrayList<HazardItem> iHazardItems = getInactiveRemoteHazardItems();

        for (HazardItem iHazardItem : iHazardItems) {
            remoteHazards.get(iHazardItem.getDeviceID()).remove(iHazardItem);
            hasInactiveHazard = true;
        }
        return hasInactiveHazard;
    }
}

class HazardItem {
    public static final int MAX_TTL_VALUE = 30;
    private Context context;
    private Marker marker;
    private int deviceID;
    private int hSeqNo;
    private Location hLocation;
    private boolean isStillValid;
    private int ttl;
    private String srcDevAddress;

    public HazardItem(Context context, int deviceID, int hSeqNo, Location hLocation, String srcDevAddress) {
        this.context = context;
        this.deviceID = deviceID;
        this.hSeqNo = hSeqNo;
        this.hLocation = hLocation;
        this.isStillValid = true;
        this.ttl = MAX_TTL_VALUE;
        this.srcDevAddress = srcDevAddress;
    }

    public int getTtl() {
        return ttl;
    }

    public void setTtl(int ttl) {
        this.ttl = ttl;
    }

    public void decreaseTTL() {
        ttl--;
    }

    public void resetTTL() {
        ttl = MAX_TTL_VALUE;
    }

    public Location gethLocation() {
        return hLocation;
    }

    public int getDeviceID() {
        return deviceID;
    }

    public int gethSeqNo() {
        return hSeqNo;
    }

    public Marker getMarker() {
        return marker;
    }

    public void setMarker(Marker marker) {
        this.marker = marker;
    }

    public boolean isStillValid() {
        return isStillValid;
    }

    public void setStillValid(boolean isStillValid) {
        this.isStillValid = isStillValid;
    }

    public void updateLocation(Location hLocation) {
        this.hLocation = hLocation;
    }

    public String getLocalMapMarkerTitle() {
        return "Local Hazard (" + hSeqNo + ")";
    }

    public String getRemoteMapMarkerTitle() {
        return "Remote Hazard (" + hSeqNo + ")";
    }

    public String getLocalMapMarkerSnippet() {
        return "A local hazard is detected here:\n (" + hLocation.getLatitude() + "," + hLocation.getLongitude() + ")";
    }

    public String getRemoteMapMarkerSnippet() {
        return "A remote hazard is detected here:\n (" + hLocation.getLatitude() + "," + hLocation.getLongitude() + ")";
    }

    @Override
    public String toString() {
        String str;
        str = deviceID + "," + hSeqNo + "," + LocationUtils.getLatLng(context, hLocation) + "," + (isStillValid ? "1" : "0");
        return str;
    }

    public String getSrcDevAddress() {
        return srcDevAddress;
    }
}