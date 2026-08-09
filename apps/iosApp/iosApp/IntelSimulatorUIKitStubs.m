#import <TargetConditionals.h>
#import <UIKit/UIKit.h>

#if TARGET_OS_SIMULATOR && defined(__x86_64__)

// Compose 1.8 links these iOS 16 symbols even when its deployment target is
// older. They are not used during normal startup on iOS 15, but Xcode 13's
// linker still requires definitions for an Intel simulator build.
@interface UIEditMenuInteraction : NSObject
@end

@implementation UIEditMenuInteraction
@end

@interface UIEditMenuConfiguration : NSObject
@end

@implementation UIEditMenuConfiguration
@end

@interface UITextLoupeSession : NSObject
@end

@implementation UITextLoupeSession
@end

UIAccessibilityTraits const UIAccessibilityTraitToggleButton = 0;

#endif
