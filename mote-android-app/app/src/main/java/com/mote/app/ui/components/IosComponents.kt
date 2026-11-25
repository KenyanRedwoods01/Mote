package com.mote.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val MoteNavy = Color(0xFF0B0D1D)
val IosCanvas = Color(0xFFF2F2F7)
val LocalNanoEmerald = Color(0xFF059669)
val CloudFlashCobalt = Color(0xFF1D4ED8)
val IosGreen = Color(0xFF34C759)

/**
 * 8dp rounded crisp card matching Mote's iOS-inspired grouped design language.
 */
@Composable
fun MoteCard(
    modifier: Modifier = Modifier,
    background: Color = Color.White,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(8.dp), ambientColor = Color(0x120B0D1D))
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(0.5.dp, Color(0x140F172A), RoundedCornerShape(8.dp))
            .padding(14.dp),
        content = content
    )
}

/**
 * Spring-animated iPhone-style toggle switch.
 */
@Composable
fun IosSpringSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) IosGreen else Color(0xFFD1D5DB),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "switchTrack"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "switchThumb"
    )

    Box(
        modifier = modifier
            .width(46.dp)
            .height(26.dp)
            .clip(CircleShape)
            .background(trackColor)
            .clickable { onCheckedChange(!checked) }
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset, y = 2.dp)
                .size(22.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

/**
 * Translucent iPhone-style top navigation bar.
 */
@Composable
fun IosTopNavBar(
    title: String,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(IosCanvas.copy(alpha = 0.92f))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MoteNavy
        )
        trailing()
    }
}

data class FloatingTab(val route: String, val label: String, val icon: ImageVector)

val moteFloatingTabs = listOf(
    FloatingTab("home", "Home", Icons.Outlined.Home),
    FloatingTab("bots", "Bots", Icons.Outlined.SmartToy),
    FloatingTab("security", "Security", Icons.Outlined.Shield),
    FloatingTab("settings", "Settings", Icons.Outlined.Settings)
)

/**
 * Persistent iPhone-style floating frosted-glass capsule bottom navigation bar
 * with a spring-gliding active white pill indicator.
 */
@Composable
fun IosFloatingBottomNav(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .shadow(10.dp, CircleShape, ambientColor = Color(0x220B0D1D))
            .clip(CircleShape)
            .background(Color(0xE8F8FAFC))
            .border(1.dp, Color(0x160F172A), CircleShape)
            .padding(5.dp)
    ) {
        val tabWidth = maxWidth / moteFloatingTabs.size
        val pillOffset by animateDpAsState(
            targetValue = tabWidth * selectedIndex,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "navPillOffset"
        )

        Box(
            modifier = Modifier
                .offset(x = pillOffset)
                .width(tabWidth)
                .height(48.dp)
                .shadow(3.dp, CircleShape, ambientColor = Color(0x1A0B0D1D))
                .clip(CircleShape)
                .background(Color.White)
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            moteFloatingTabs.forEachIndexed { idx, tab ->
                val active = idx == selectedIndex
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(CircleShape)
                        .clickable { onSelect(idx) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = if (active) MoteNavy else Color(0xFF64748B),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = tab.label,
                        fontSize = 10.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) MoteNavy else Color(0xFF64748B)
                    )
                }
            }
        }
    }
}
