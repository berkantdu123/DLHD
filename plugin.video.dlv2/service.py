from functions import get_schedule_and_channels
import variables as var

if __name__ == '__main__':
    get_schedule_and_channels()
    if var.get_setting_bool('disable_notify') is False:
        var.notify_dialog(var.addon_name, 'Schedule refreshed.', icon=var.addon_icon)
    