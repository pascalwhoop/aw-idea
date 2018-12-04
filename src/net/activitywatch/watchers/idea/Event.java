
package net.activitywatch.watchers.idea;


import java.math.BigDecimal;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Event message
 */
public class Event {

    public Event(EventData data) {
        Date now = new Date();
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'"); // Quoted "Z" to indicate UTC, no timezone offset
        this.timestamp = df.format(now);

        this.duration = ActivityWatch.getCurrentTimestamp().subtract(ActivityWatch.lastTime);
        this.data = data;
    }

    public final String timestamp;
    public final BigDecimal duration;
    public final EventData data;
}
